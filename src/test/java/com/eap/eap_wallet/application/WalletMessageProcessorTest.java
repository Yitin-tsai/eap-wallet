package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderSubmittedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletMessageProcessorTest {

    @Mock WalletOrderReservationProcessor reservationProcessor;
    @Mock WalletOrderCancellationAppender cancellationAppender;
    @Mock WalletTradeSettlementAppender settlementAppender;
    @Mock WalletMessageInbox inbox;
    @Mock NamedParameterJdbcTemplate jdbc;

    private ObjectMapper objectMapper;
    private WalletMessageProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        processor = new WalletMessageProcessor(
                reservationProcessor, cancellationAppender, settlementAppender,
                inbox, jdbc, objectMapper);
    }

    @Test
    void reservationAndInboxApplied_shouldShareProcessorTransactionBoundary() throws Exception {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(UUID.randomUUID()).userId(UUID.randomUUID())
                .orderType("BUY").price(100).amount(5).build();
        WalletMessageInbox.InboxEntry entry = new WalletMessageInbox.InboxEntry(
                WalletMessageInbox.MessageType.ORDER_SUBMITTED,
                event.getOrderId().toString(), objectMapper.writeValueAsString(event), "hash", 1);
        when(inbox.markApplied(entry, "worker")).thenReturn(true);

        processor.process(entry, "worker");

        verify(reservationProcessor).reserve(event);
        verify(inbox).markApplied(entry, "worker");
    }

    @Test
    void cancelledResult_shouldReleaseAndWriteOneIntegrationOutboxFact() throws Exception {
        OrderCancellationResultEvent event = OrderCancellationResultEvent.builder()
                .cancellationId(UUID.randomUUID()).orderId(UUID.randomUUID()).userId(UUID.randomUUID())
                .outcome(OrderCancellationResultEvent.CANCELLED)
                .orderType("SELL").limitPrice(100).cancelledAmount(5).build();
        WalletMessageInbox.InboxEntry entry = new WalletMessageInbox.InboxEntry(
                WalletMessageInbox.MessageType.ORDER_CANCELLATION_RESULT,
                event.getCancellationId().toString(), objectMapper.writeValueAsString(event), "hash", 1);
        when(cancellationAppender.release(event)).thenReturn(
                new WalletOrderCancellationAppender.CancellationOutcome(0, 0, 1, 1));
        when(jdbc.update(contains("order_asset_release_publications"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        when(jdbc.update(contains("INSERT INTO wallet_service.outbox"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        when(inbox.markApplied(entry, "worker")).thenReturn(true);

        processor.process(entry, "worker");

        verify(cancellationAppender).release(event);
        verify(jdbc).update(contains("'OrderAssetReservationReleasedEvent'"),
                any(MapSqlParameterSource.class));
        verify(inbox).markApplied(entry, "worker");
    }

    @Test
    void tradeSettlementAndInboxApplied_shouldShareProcessorTransactionBoundary() throws Exception {
        TradeExecutedEvent event = TradeExecutedEvent.builder()
                .tradeId("market-101")
                .buyerId(UUID.randomUUID()).sellerId(UUID.randomUUID())
                .buyerOrderId(UUID.randomUUID()).sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(120).originSellerPrice(100)
                .dealPrice(110).quantity(5).build();
        WalletMessageInbox.InboxEntry entry = new WalletMessageInbox.InboxEntry(
                WalletMessageInbox.MessageType.TRADE_EXECUTED,
                event.getTradeId(), objectMapper.writeValueAsString(event), "trade-payload-hash", 1);
        when(settlementAppender.append(any(TradeExecutedEvent.class), any(), eq("trade-payload-hash")))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        2, 0, 1, 1, 1, 600, 50, 550, null));
        when(inbox.markApplied(entry, "worker")).thenReturn(true);

        WalletMessageProcessor.ProcessingOutcome outcome = processor.process(entry, "worker");

        verify(settlementAppender).append(any(TradeExecutedEvent.class), any(), eq("trade-payload-hash"));
        verify(inbox).markApplied(entry, "worker");
        org.junit.jupiter.api.Assertions.assertEquals(
                WalletMessageProcessor.ProcessingOutcome.TRADE_SETTLED, outcome);
    }

    @Test
    void selfTrade_shouldBeRejectedBeforeWalletMutation() throws Exception {
        UUID userId = UUID.randomUUID();
        TradeExecutedEvent event = validTradeEvent();
        event.setBuyerId(userId);
        event.setSellerId(userId);
        WalletMessageInbox.InboxEntry entry = tradeEntry(event);

        assertThrows(IllegalArgumentException.class, () -> processor.process(entry, "worker"));

        verify(settlementAppender, never()).append(any(), any(), any());
    }

    @Test
    void dealBelowSellerLimit_shouldBeRejectedBeforeWalletMutation() throws Exception {
        TradeExecutedEvent event = validTradeEvent();
        event.setOriginSellerPrice(111);
        event.setDealPrice(110);
        WalletMessageInbox.InboxEntry entry = tradeEntry(event);

        assertThrows(IllegalArgumentException.class, () -> processor.process(entry, "worker"));

        verify(settlementAppender, never()).append(any(), any(), any());
    }

    private WalletMessageInbox.InboxEntry tradeEntry(TradeExecutedEvent event) throws Exception {
        return new WalletMessageInbox.InboxEntry(
                WalletMessageInbox.MessageType.TRADE_EXECUTED,
                event.getTradeId(), objectMapper.writeValueAsString(event), "trade-payload-hash", 1);
    }

    private TradeExecutedEvent validTradeEvent() {
        return TradeExecutedEvent.builder()
                .tradeId("market-validation-101")
                .buyerId(UUID.randomUUID()).sellerId(UUID.randomUUID())
                .buyerOrderId(UUID.randomUUID()).sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(120).originSellerPrice(100)
                .dealPrice(110).quantity(5).build();
    }
}

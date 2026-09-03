package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderSubmittedEvent;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletMessageProcessorTest {

    @Mock WalletOrderReservationProcessor reservationProcessor;
    @Mock WalletOrderCancellationAppender cancellationAppender;
    @Mock WalletMessageInbox inbox;
    @Mock NamedParameterJdbcTemplate jdbc;

    private ObjectMapper objectMapper;
    private WalletMessageProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        processor = new WalletMessageProcessor(
                reservationProcessor, cancellationAppender, inbox, jdbc, objectMapper);
    }

    @Test
    void reservationAndInboxApplied_shouldShareProcessorTransactionBoundary() throws Exception {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(UUID.randomUUID()).userId(UUID.randomUUID())
                .orderType("BUY").price(100).amount(5).build();
        WalletMessageInbox.InboxEntry entry = new WalletMessageInbox.InboxEntry(
                WalletMessageInbox.MessageType.ORDER_SUBMITTED,
                event.getOrderId(), objectMapper.writeValueAsString(event), 1);
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
                event.getCancellationId(), objectMapper.writeValueAsString(event), 1);
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
}

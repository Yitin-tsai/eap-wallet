package com.eap.eap_wallet.configuration.recovery;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.recovery.BrokerReplayPreflightDecision;
import com.eap.common.recovery.BrokerReplayPreflightRequest;
import com.eap.eap_wallet.application.WalletMessageInbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.eap.common.constants.RabbitMQConstants.TRADE_EXCHANGE;
import static com.eap.common.constants.RabbitMQConstants.TRADE_EXECUTED_KEY;
import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WalletBrokerReplayPreflightServiceTest {

    private WalletMessageInbox inbox;
    private ObjectMapper objectMapper;
    private WalletBrokerReplayPreflightService service;

    @BeforeEach
    void setUp() {
        inbox = mock(WalletMessageInbox.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new WalletBrokerReplayPreflightService(inbox, objectMapper);
    }

    @Test
    void missingValidTradeShouldBeEligible() throws Exception {
        TradeExecutedEvent event = validEvent();
        when(inbox.inspectTradeExecuted(event)).thenReturn(
                new WalletMessageInbox.TradeInboxSnapshot(false, null, true, null, false));

        var result = service.inspect(request(event));

        assertThat(result.decision()).isEqualTo(BrokerReplayPreflightDecision.ELIGIBLE);
        assertThat(result.safeToReplay()).isTrue();
        assertThat(result.messageId()).isEqualTo(event.getTradeId());
    }

    @Test
    void pendingExactTradeShouldStayWithDurableInbox() throws Exception {
        TradeExecutedEvent event = validEvent();
        when(inbox.inspectTradeExecuted(event)).thenReturn(
                new WalletMessageInbox.TradeInboxSnapshot(
                        true, "FAILED_RETRYABLE", true, "RETRY_EXHAUSTED_TRANSIENT", false));

        var result = service.inspect(request(event));

        assertThat(result.decision()).isEqualTo(BrokerReplayPreflightDecision.ALREADY_DURABLE);
        assertThat(result.safeToReplay()).isFalse();
    }

    @Test
    void conflictingTradeIdentityShouldBeRejected() throws Exception {
        TradeExecutedEvent event = validEvent();
        when(inbox.inspectTradeExecuted(event)).thenReturn(
                new WalletMessageInbox.TradeInboxSnapshot(
                        true, "APPLIED", false, "IDENTITY_CONFLICT", true));

        var result = service.inspect(request(event));

        assertThat(result.decision()).isEqualTo(BrokerReplayPreflightDecision.IDENTITY_CONFLICT);
        assertThat(result.safeToReplay()).isFalse();
    }

    @Test
    void malformedOrWrongRouteShouldFailClosed() {
        var wrongRoute = service.inspect(new BrokerReplayPreflightRequest(
                "order.tradeExecuted.queue", TRADE_EXCHANGE, TRADE_EXECUTED_KEY, "{}"));
        var malformed = service.inspect(new BrokerReplayPreflightRequest(
                WALLET_TRADE_EXECUTED_QUEUE, TRADE_EXCHANGE, TRADE_EXECUTED_KEY, "{}"));

        assertThat(wrongRoute.decision()).isEqualTo(BrokerReplayPreflightDecision.UNSUPPORTED_ROUTE);
        assertThat(malformed.decision()).isEqualTo(BrokerReplayPreflightDecision.INVALID_PAYLOAD);
        verifyNoInteractions(inbox);
    }

    @Test
    void overlongTradeIdentityShouldBeRejectedBeforeDatabaseInspection() throws Exception {
        TradeExecutedEvent event = validEvent();
        event.setTradeId("t".repeat(TradeExecutedEvent.MAX_TRADE_ID_LENGTH + 1));

        var result = service.inspect(request(event));

        assertThat(result.decision()).isEqualTo(BrokerReplayPreflightDecision.INVALID_PAYLOAD);
        verifyNoInteractions(inbox);
    }

    private BrokerReplayPreflightRequest request(TradeExecutedEvent event) throws Exception {
        return new BrokerReplayPreflightRequest(
                WALLET_TRADE_EXECUTED_QUEUE,
                TRADE_EXCHANGE,
                TRADE_EXECUTED_KEY,
                objectMapper.writeValueAsString(event));
    }

    private TradeExecutedEvent validEvent() {
        return TradeExecutedEvent.builder()
                .tradeId("market-replay-101")
                .buyerId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .buyerOrderId(UUID.randomUUID())
                .sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(120)
                .originSellerPrice(100)
                .dealPrice(110)
                .quantity(5)
                .build();
    }
}

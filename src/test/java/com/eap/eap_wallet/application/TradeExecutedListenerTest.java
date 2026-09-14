package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeExecutedListenerTest {

    @Mock
    private WalletMessageInbox inbox;

    @Mock
    private WalletMetrics walletMetrics;

    @Test
    void handleTradeExecuted_shouldPersistDurableInboxBeforeReturning() {
        TradeExecutedEvent event = event();
        when(inbox.receiveTradeExecuted(event)).thenReturn(WalletMessageInbox.ReceiveOutcome.ACCEPTED);
        TradeExecutedListener listener = new TradeExecutedListener(inbox, walletMetrics);

        listener.handleTradeExecuted(event);

        verify(inbox).receiveTradeExecuted(event);
        verify(walletMetrics).tradeSettlementConsumed();
    }

    @Test
    void handleTradeExecuted_duplicateTrade_shouldReturnAfterDurableIdentityCheck() {
        TradeExecutedEvent event = event();
        when(inbox.receiveTradeExecuted(event)).thenReturn(WalletMessageInbox.ReceiveOutcome.DUPLICATE);
        TradeExecutedListener listener = new TradeExecutedListener(inbox, walletMetrics);

        listener.handleTradeExecuted(event);

        verify(inbox).receiveTradeExecuted(event);
        verify(walletMetrics).tradeInboxDuplicate();
    }

    @Test
    void handleTradeExecuted_inboxFailure_shouldPropagateWithoutAcknowledging() {
        TradeExecutedEvent event = event();
        RuntimeException failure = new RuntimeException("database unavailable");
        doThrow(failure).when(inbox).receiveTradeExecuted(event);
        TradeExecutedListener listener = new TradeExecutedListener(inbox, walletMetrics);

        assertThrows(RuntimeException.class, () -> listener.handleTradeExecuted(event));

        verify(inbox).receiveTradeExecuted(event);
    }

    private TradeExecutedEvent event() {
        return event("trade-1");
    }

    private TradeExecutedEvent event(String tradeId) {
        return TradeExecutedEvent.builder()
                .tradeId(tradeId)
                .legacyMatchId(1001)
                .buyerId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .buyerOrderId(UUID.randomUUID())
                .sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(120)
                .originSellerPrice(100)
                .dealPrice(110)
                .quantity(10)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}

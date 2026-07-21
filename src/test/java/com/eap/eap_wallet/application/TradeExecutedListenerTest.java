package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeExecutedListenerTest {

    @Mock
    private WalletTradeSettlementAppender settlementAppender;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private WalletMetrics walletMetrics;

    private TradeExecutedListener listener;

    @BeforeEach
    void setUp() {
        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        listener = new TradeExecutedListener(
                settlementAppender,
                transactionManager,
                walletMetrics);
    }

    @Test
    void handleTradeExecuted_shouldAppendSettlement() {
        TradeExecutedEvent event = event();
        when(settlementAppender.append(eq(event), eq(event.getOccurredAt())))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        1, 1, 1, 1200, 100, 1100, event.getOccurredAt()));

        listener.handleTradeExecuted(event);

        verify(settlementAppender).append(eq(event), eq(event.getOccurredAt()));
    }

    @Test
    void handleTradeExecuted_duplicateTrade_shouldSkipAsNoop() {
        TradeExecutedEvent event = event();
        when(settlementAppender.append(eq(event), eq(event.getOccurredAt())))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        0, 0, 0, 1200, 100, 1100, event.getOccurredAt()));

        listener.handleTradeExecuted(event);

        verify(settlementAppender).append(eq(event), eq(event.getOccurredAt()));
    }

    private TradeExecutedEvent event() {
        return TradeExecutedEvent.builder()
                .tradeId("trade-1")
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

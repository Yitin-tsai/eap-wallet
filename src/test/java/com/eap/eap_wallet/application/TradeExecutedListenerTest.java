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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void handleTradeExecutedBatch_nonOverlappingUsers_shouldAppendBatch() {
        TradeExecutedEvent first = event("trade-1");
        TradeExecutedEvent second = event("trade-2");
        when(settlementAppender.appendBatch(List.of(first, second)))
                .thenReturn(new WalletTradeSettlementAppender.BatchSettlementOutcome(
                        2, 0, 2, 2, 2));

        listener.handleTradeExecutedBatch(List.of(first, second));

        verify(settlementAppender).appendBatch(List.of(first, second));
        verify(settlementAppender, never()).append(any(), any());
        verify(walletMetrics).tradeSettlementCompleted(2);
        verify(walletMetrics).tradeSettlementBatchApplied(2);
    }

    @Test
    void handleTradeExecutedBatch_overlappingUsers_shouldFallbackToSingleSettlements() {
        UUID sharedBuyer = UUID.randomUUID();
        TradeExecutedEvent first = event("trade-1");
        first.setBuyerId(sharedBuyer);
        TradeExecutedEvent second = event("trade-2");
        second.setBuyerId(sharedBuyer);
        when(settlementAppender.append(eq(first), eq(first.getOccurredAt())))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        1, 1, 1, 1200, 100, 1100, first.getOccurredAt()));
        when(settlementAppender.append(eq(second), eq(second.getOccurredAt())))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        1, 1, 1, 1200, 100, 1100, second.getOccurredAt()));

        listener.handleTradeExecutedBatch(List.of(first, second));

        verify(settlementAppender, never()).appendBatch(any());
        verify(settlementAppender).append(eq(first), eq(first.getOccurredAt()));
        verify(settlementAppender).append(eq(second), eq(second.getOccurredAt()));
        verify(walletMetrics, times(2)).tradeSettlementCompleted();
        verify(walletMetrics).tradeSettlementBatchFallback("overlapping_user", 2);
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

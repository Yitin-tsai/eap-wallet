package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradeExecutedListener {

    private final WalletTradeSettlementAppender settlementAppender;
    private final PlatformTransactionManager transactionManager;
    private final WalletMetrics walletMetrics;

    @RabbitListener(
            queues = WALLET_TRADE_EXECUTED_QUEUE,
            containerFactory = "walletTradeExecutedBatchListenerContainerFactory",
            concurrency = "${eap.wallet.listeners.trade-executed.concurrency:4}")
    public void handleTradeExecutedBatch(List<TradeExecutedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        long batchStartedAt = System.nanoTime();
        walletMetrics.tradeSettlementConsumed(events.size());
        try {
            if (events.size() == 1) {
                walletMetrics.tradeSettlementBatchFallback("singleton_batch", 1);
                handleSingleTradeExecuted(events.get(0), false);
                return;
            }
            if (hasOverlappingUsers(events)) {
                walletMetrics.tradeSettlementBatchFallback("overlapping_user", events.size());
                handleTradesIndividually(events);
                return;
            }
            try {
                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                WalletTradeSettlementAppender.BatchSettlementOutcome outcome =
                        txTemplate.execute(status -> {
                            WalletTradeSettlementAppender.BatchSettlementOutcome result =
                                    settlementAppender.appendBatch(events);
                            if (result == null || !result.completed()) {
                                throw new BatchSettlementNotAppliedException(
                                        result != null && result.hasExistingSettlements()
                                                ? "existing_settlement"
                                                : "incomplete_batch");
                            }
                            return result;
                        });
                walletMetrics.tradeSettlementCompleted(events.size());
                walletMetrics.tradeSettlementBatchApplied(events.size());
                log.debug("Trade wallet settlement batch completed: size={}", events.size());
            } catch (BatchSettlementNotAppliedException e) {
                walletMetrics.tradeSettlementBatchFallback(e.reason(), events.size());
                handleTradesIndividually(events);
            } catch (DataIntegrityViolationException e) {
                walletMetrics.tradeSettlementBatchFallback("data_integrity", events.size());
                handleTradesIndividually(events);
            } catch (ObjectOptimisticLockingFailureException e) {
                walletMetrics.tradeSettlementBatchFallback("optimistic_lock", events.size());
                handleTradesIndividually(events);
            }
        } finally {
            walletMetrics.recordTradeSettlementBatch(Duration.ofNanos(System.nanoTime() - batchStartedAt));
        }
    }

    public void handleTradeExecuted(TradeExecutedEvent event) {
        handleSingleTradeExecuted(event, true);
    }

    private void handleTradesIndividually(List<TradeExecutedEvent> events) {
        for (TradeExecutedEvent event : events) {
            handleSingleTradeExecuted(event, false);
        }
    }

    private void handleSingleTradeExecuted(TradeExecutedEvent event, boolean recordConsumed) {
        long processingStartedAt = System.nanoTime();
        if (recordConsumed) {
            walletMetrics.tradeSettlementConsumed();
        }
        log.debug("Received TradeExecutedEvent for wallet settlement: tradeId={}, legacyMatchId={}",
                event.getTradeId(), event.getLegacyMatchId());

        try {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            int maxRetries = 3;
            LocalDateTime settledAt = event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt();

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                long transactionStartedAt = System.nanoTime();
                try {
                    txTemplate.executeWithoutResult(status -> settle(event, settledAt));
                    return;
                } catch (DataIntegrityViolationException e) {
                    walletMetrics.tradeSettlementDuplicateSkipped();
                    log.debug("Duplicate TradeExecutedEvent settlement skipped: tradeId={}", event.getTradeId());
                    return;
                } catch (ObjectOptimisticLockingFailureException e) {
                    walletMetrics.optimisticLockRetry();
                    if (attempt == maxRetries) {
                        walletMetrics.tradeSettlementFailed();
                        log.error("Trade settlement failed after optimistic lock retries: tradeId={}",
                                event.getTradeId(), e);
                        throw e;
                    }
                    log.warn("Optimistic lock conflict on trade settlement attempt {}/{}: tradeId={}",
                            attempt, maxRetries, event.getTradeId());
                } catch (RuntimeException e) {
                    walletMetrics.tradeSettlementFailed();
                    throw e;
                } finally {
                    walletMetrics.recordTradeSettlementTransaction(
                            Duration.ofNanos(System.nanoTime() - transactionStartedAt));
                }
            }
        } finally {
            walletMetrics.recordTradeSettlementProcessing(
                    Duration.ofNanos(System.nanoTime() - processingStartedAt));
        }
    }

    private void settle(TradeExecutedEvent event, LocalDateTime settledAt) {
        WalletTradeSettlementAppender.SettlementOutcome outcome =
                settlementAppender.append(event, settledAt);
        if (outcome.duplicate()) {
            walletMetrics.tradeSettlementDuplicateSkipped();
            log.debug("Trade already settled, skipping duplicate: tradeId={}", event.getTradeId());
            return;
        }

        walletMetrics.tradeSettlementCompleted();
        log.debug("Trade wallet settlement completed: tradeId={}, buyerId={}, sellerId={}, dealCurrency={}, quantity={}",
                event.getTradeId(), event.getBuyerId(), event.getSellerId(),
                outcome.dealCurrency(), event.getQuantity());
    }

    private boolean hasOverlappingUsers(List<TradeExecutedEvent> events) {
        Set<UUID> userIds = new HashSet<>(events.size() * 2);
        for (TradeExecutedEvent event : events) {
            if (event.getBuyerId() == null || event.getSellerId() == null) {
                return true;
            }
            if (!userIds.add(event.getBuyerId()) || !userIds.add(event.getSellerId())) {
                return true;
            }
        }
        return false;
    }

    private static class BatchSettlementNotAppliedException extends RuntimeException {
        private final String reason;

        private BatchSettlementNotAppliedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }
}

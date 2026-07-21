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
            concurrency = "${eap.wallet.listeners.trade-executed.concurrency:4}")
    public void handleTradeExecuted(TradeExecutedEvent event) {
        long processingStartedAt = System.nanoTime();
        walletMetrics.tradeSettlementConsumed();
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
}

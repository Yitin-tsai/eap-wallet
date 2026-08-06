package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

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
            LocalDateTime settledAt = event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt();
            long transactionStartedAt = System.nanoTime();
            try {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> settle(event, settledAt));
            } catch (RuntimeException e) {
                walletMetrics.tradeSettlementFailed();
                throw e;
            } finally {
                walletMetrics.recordTradeSettlementTransaction(
                        Duration.ofNanos(System.nanoTime() - transactionStartedAt));
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

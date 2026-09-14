package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
public class WalletMessageReconciler {

    private final WalletMessageInbox inbox;
    private final WalletMessageProcessor processor;
    private final WalletMessageErrorClassifier classifier;
    private final WalletMetrics walletMetrics;
    private final String owner = UUID.randomUUID().toString();
    private final int batchSize;
    private final long leaseMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public WalletMessageReconciler(
            WalletMessageInbox inbox,
            WalletMessageProcessor processor,
            WalletMessageErrorClassifier classifier,
            WalletMetrics walletMetrics,
            @Value("${eap.wallet.inbox-reconciler.batch-size:100}") int batchSize,
            @Value("${eap.wallet.inbox-reconciler.lease-ms:30000}") long leaseMs,
            @Value("${eap.wallet.inbox-reconciler.max-attempts:20}") int maxAttempts,
            @Value("${eap.wallet.inbox-reconciler.initial-backoff-ms:250}") long initialBackoffMs,
            @Value("${eap.wallet.inbox-reconciler.max-backoff-ms:30000}") long maxBackoffMs) {
        this.inbox = inbox;
        this.processor = processor;
        this.classifier = classifier;
        this.walletMetrics = walletMetrics;
        this.batchSize = Math.max(1, batchSize);
        this.leaseMs = Math.max(1, leaseMs);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(1, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
    }

    @Scheduled(
            fixedDelayString = "${eap.wallet.inbox-reconciler.poll-interval-ms:100}",
            initialDelayString = "${eap.wallet.inbox-reconciler.initial-delay-ms:500}")
    public void reconcile() {
        List<WalletMessageInbox.InboxEntry> entries = inbox.claimRetryable(batchSize, owner, leaseMs);
        for (WalletMessageInbox.InboxEntry entry : entries) {
            process(entry);
        }
    }

    private void process(WalletMessageInbox.InboxEntry entry) {
        long startedAt = System.nanoTime();
        try {
            WalletMessageProcessor.ProcessingOutcome outcome = processor.process(entry, owner);
            if (outcome == WalletMessageProcessor.ProcessingOutcome.TRADE_SETTLED) {
                walletMetrics.tradeSettlementCompleted();
            } else if (outcome == WalletMessageProcessor.ProcessingOutcome.TRADE_DUPLICATE) {
                walletMetrics.tradeSettlementDuplicateSkipped();
            }
        } catch (Exception failure) {
            if (entry.messageType() == WalletMessageInbox.MessageType.TRADE_EXECUTED) {
                walletMetrics.tradeSettlementFailed();
            }
            WalletMessageErrorClassifier.Classification classification = classifier.classify(failure);
            if (!classification.retryable() || entry.attemptCount() >= maxAttempts) {
                boolean marked = inbox.markPermanent(entry, owner, classification.errorType(), failure);
                if (marked) {
                    log.error("Wallet inbox message permanently failed: type={}, id={}, attempts={}",
                            entry.messageType(), entry.messageId(), entry.attemptCount(), failure);
                } else {
                    log.warn("Wallet inbox lease changed before permanent failure update: type={}, id={}",
                            entry.messageType(), entry.messageId());
                }
                return;
            }
            long delayMs = retryDelayWithJitter(entry.attemptCount());
            boolean rescheduled = inbox.reschedule(entry, owner, "FAILED_RETRYABLE",
                    classification.errorType(), failure, delayMs);
            if (rescheduled) {
                log.warn("Wallet inbox retry scheduled: type={}, id={}, attempt={}, delayMs={}, errorType={}",
                        entry.messageType(), entry.messageId(), entry.attemptCount(), delayMs,
                        classification.errorType());
            } else {
                log.warn("Wallet inbox lease changed before retry update: type={}, id={}",
                        entry.messageType(), entry.messageId());
            }
        } finally {
            if (entry.messageType() == WalletMessageInbox.MessageType.TRADE_EXECUTED) {
                walletMetrics.recordTradeSettlementTransaction(
                        java.time.Duration.ofNanos(System.nanoTime() - startedAt));
            }
        }
    }

    private long retryDelayWithJitter(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
        long exponential;
        try {
            exponential = Math.multiplyExact(initialBackoffMs, 1L << exponent);
        } catch (ArithmeticException ignored) {
            exponential = maxBackoffMs;
        }
        long capped = Math.min(maxBackoffMs, exponential);
        long jitterBound = Math.max(1, capped / 4);
        return Math.min(maxBackoffMs, capped + ThreadLocalRandom.current().nextLong(jitterBound));
    }
}

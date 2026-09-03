package com.eap.eap_wallet.application;

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
            @Value("${eap.wallet.inbox-reconciler.batch-size:100}") int batchSize,
            @Value("${eap.wallet.inbox-reconciler.lease-ms:30000}") long leaseMs,
            @Value("${eap.wallet.inbox-reconciler.max-attempts:20}") int maxAttempts,
            @Value("${eap.wallet.inbox-reconciler.initial-backoff-ms:250}") long initialBackoffMs,
            @Value("${eap.wallet.inbox-reconciler.max-backoff-ms:30000}") long maxBackoffMs) {
        this.inbox = inbox;
        this.processor = processor;
        this.classifier = classifier;
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
        try {
            processor.process(entry, owner);
        } catch (Exception failure) {
            WalletMessageErrorClassifier.Classification classification = classifier.classify(failure);
            if (!classification.retryable() || entry.attemptCount() >= maxAttempts) {
                inbox.markPermanent(entry, owner, classification.errorType(), failure);
                log.error("Wallet inbox message permanently failed: type={}, id={}, attempts={}",
                        entry.messageType(), entry.messageId(), entry.attemptCount(), failure);
                return;
            }
            long delayMs = retryDelayWithJitter(entry.attemptCount());
            inbox.reschedule(entry, owner, "FAILED_RETRYABLE",
                    classification.errorType(), failure, delayMs);
            log.warn("Wallet inbox retry scheduled: type={}, id={}, attempt={}, delayMs={}, errorType={}",
                    entry.messageType(), entry.messageId(), entry.attemptCount(), delayMs,
                    classification.errorType());
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

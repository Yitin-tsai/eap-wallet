package com.eap.eap_wallet.configuration.observability;

import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class WalletMetrics {

    private final Counter orderSubmittedConsumedCounter;
    private final Counter orderSubmittedDuplicateSkippedCounter;
    private final Counter optimisticLockRetryCounter;
    private final Timer orderSubmittedProcessingTimer;
    private final Timer orderSubmittedTransactionTimer;
    private final Timer orderSubmittedIdempotencyClaimTimer;
    private final Timer orderSubmittedWalletLookupTimer;
    private final Timer orderSubmittedOutboxWriteTimer;
    private final Counter outboxPublishedCounter;
    private final Counter outboxPublishFailedCounter;
    private final Counter outboxRetryScheduledCounter;
    private final Counter outboxRequeuedCounter;
    private final Timer outboxPublishTimer;
    private final Timer outboxSelectTimer;
    private final Timer outboxMarkSentTimer;
    private final Counter tradeSettlementConsumedCounter;
    private final Counter tradeSettlementCompletedCounter;
    private final Counter tradeSettlementDuplicateSkippedCounter;
    private final Counter tradeSettlementFailedCounter;
    private final Timer tradeSettlementProcessingTimer;
    private final Timer tradeSettlementSerializationTimer;
    private final Timer tradeSettlementTransactionTimer;
    private final Timer tradeSettlementCteTimer;

    public WalletMetrics(MeterRegistry registry, OutboxRepository outboxRepository) {
        this.orderSubmittedConsumedCounter = Counter.builder("eap_wallet_order_submitted_consumed_total")
                .description("Total OrderSubmittedEvent messages consumed by wallet service")
                .register(registry);
        this.orderSubmittedDuplicateSkippedCounter = Counter.builder("eap_wallet_order_submitted_duplicate_skipped_total")
                .description("Total duplicate OrderSubmittedEvent messages skipped by idempotency claim")
                .register(registry);
        this.optimisticLockRetryCounter = Counter.builder("eap_wallet_optimistic_lock_retry_total")
                .description("Total optimistic lock retries while processing wallet events")
                .register(registry);
        this.orderSubmittedProcessingTimer = Timer.builder("eap_wallet_order_submitted_processing_duration")
                .description("Time spent processing OrderSubmittedEvent in wallet service")
                .publishPercentileHistogram()
                .register(registry);
        this.orderSubmittedTransactionTimer = stageTimer(
                registry,
                "eap_wallet_order_submitted_transaction_duration",
                "Time spent executing and committing the wallet order transaction");
        this.orderSubmittedIdempotencyClaimTimer = stageTimer(
                registry,
                "eap_wallet_order_submitted_idempotency_claim_duration",
                "Time spent persistently claiming an orderId for idempotent processing");
        this.orderSubmittedWalletLookupTimer = stageTimer(
                registry,
                "eap_wallet_order_submitted_wallet_lookup_duration",
                "Time spent loading the wallet for an order");
        this.orderSubmittedOutboxWriteTimer = stageTimer(
                registry,
                "eap_wallet_order_submitted_outbox_write_duration",
                "Time spent serializing and persisting the wallet result outbox event");

        Gauge.builder("eap_wallet_outbox_pending", outboxRepository, repo -> repo.countByStatus("PENDING"))
                .description("Current number of pending wallet outbox records")
                .register(registry);

        Gauge.builder("eap_wallet_outbox_oldest_pending_age_seconds", outboxRepository, repo ->
                        repo.findFirstByStatusOrderByCreatedAtAsc("PENDING")
                                .map(entry -> Math.max(0L, Duration.between(entry.getCreatedAt(), LocalDateTime.now()).toSeconds()))
                                .orElse(0L))
                .description("Age in seconds of the oldest pending wallet outbox record")
                .register(registry);

        Gauge.builder("eap_wallet_outbox_failed", outboxRepository, repo -> repo.countByStatus("FAILED"))
                .description("Current number of permanently failed wallet outbox records")
                .register(registry);

        this.outboxPublishedCounter = Counter.builder("eap_wallet_outbox_published_total")
                .description("Total wallet outbox records confirmed by RabbitMQ")
                .register(registry);
        this.outboxPublishFailedCounter = Counter.builder("eap_wallet_outbox_publish_failed_total")
                .description("Total wallet outbox publish attempts that failed or were not confirmed")
                .register(registry);
        this.outboxRetryScheduledCounter = Counter.builder("eap_wallet_outbox_retry_scheduled_total")
                .description("Total wallet outbox publish retries scheduled with backoff")
                .register(registry);
        this.outboxRequeuedCounter = Counter.builder("eap_wallet_outbox_requeued_total")
                .description("Total permanently failed wallet outbox records manually requeued")
                .register(registry);
        this.outboxPublishTimer = Timer.builder("eap_wallet_outbox_publish_duration")
                .description("Time spent publishing and confirming a wallet outbox record")
                .publishPercentileHistogram()
                .register(registry);
        this.outboxSelectTimer = stageTimer(
                registry,
                "eap_wallet_outbox_select_duration",
                "Time spent selecting pending wallet outbox records");
        this.outboxMarkSentTimer = stageTimer(
                registry,
                "eap_wallet_outbox_mark_sent_duration",
                "Time spent marking confirmed wallet outbox records as SENT");
        this.tradeSettlementConsumedCounter = Counter.builder("eap_wallet_trade_settlement_consumed_total")
                .description("Total TradeExecutedEvent messages consumed by wallet settlement")
                .register(registry);
        this.tradeSettlementCompletedCounter = Counter.builder("eap_wallet_trade_settlement_completed_total")
                .description("Total wallet trade settlements completed")
                .register(registry);
        this.tradeSettlementDuplicateSkippedCounter = Counter.builder("eap_wallet_trade_settlement_duplicate_skipped_total")
                .description("Total duplicate wallet trade settlements skipped")
                .register(registry);
        this.tradeSettlementFailedCounter = Counter.builder("eap_wallet_trade_settlement_failed_total")
                .description("Total wallet trade settlement failures")
                .register(registry);
        this.tradeSettlementProcessingTimer = stageTimer(
                registry,
                "eap_wallet_trade_settlement_processing_duration",
                "Time spent processing TradeExecutedEvent in wallet service");
        this.tradeSettlementSerializationTimer = stageTimer(
                registry,
                "eap_wallet_trade_settlement_serialization_duration",
                "Time spent serializing WalletTradeSettledEvent payload");
        this.tradeSettlementTransactionTimer = stageTimer(
                registry,
                "eap_wallet_trade_settlement_transaction_duration",
                "Time spent executing and committing wallet trade settlement transaction");
        this.tradeSettlementCteTimer = stageTimer(
                registry,
                "eap_wallet_trade_settlement_cte_duration",
                "Time spent executing wallet trade settlement SQL CTE");
    }

    public void orderSubmittedConsumed() {
        orderSubmittedConsumedCounter.increment();
    }

    public void orderSubmittedDuplicateSkipped() {
        orderSubmittedDuplicateSkippedCounter.increment();
    }

    public void optimisticLockRetry() {
        optimisticLockRetryCounter.increment();
    }

    public void recordOrderSubmittedProcessing(Duration duration) {
        orderSubmittedProcessingTimer.record(duration);
    }

    public void recordOrderSubmittedTransaction(Duration duration) {
        orderSubmittedTransactionTimer.record(duration);
    }

    public void recordOrderSubmittedIdempotencyClaim(Duration duration) {
        orderSubmittedIdempotencyClaimTimer.record(duration);
    }

    public void recordOrderSubmittedWalletLookup(Duration duration) {
        orderSubmittedWalletLookupTimer.record(duration);
    }

    public void recordOrderSubmittedOutboxWrite(Duration duration) {
        orderSubmittedOutboxWriteTimer.record(duration);
    }

    public void outboxPublished() {
        outboxPublishedCounter.increment();
    }

    public void outboxPublishFailed() {
        outboxPublishFailedCounter.increment();
    }

    public void outboxRetryScheduled() {
        outboxRetryScheduledCounter.increment();
    }

    public void outboxRequeued() {
        outboxRequeuedCounter.increment();
    }

    public void recordOutboxPublish(Duration duration) {
        outboxPublishTimer.record(duration);
    }

    public void recordOutboxSelect(Duration duration) {
        outboxSelectTimer.record(duration);
    }

    public void recordOutboxMarkSent(Duration duration) {
        outboxMarkSentTimer.record(duration);
    }

    public void tradeSettlementConsumed() {
        tradeSettlementConsumedCounter.increment();
    }

    public void tradeSettlementCompleted() {
        tradeSettlementCompletedCounter.increment();
    }

    public void tradeSettlementDuplicateSkipped() {
        tradeSettlementDuplicateSkippedCounter.increment();
    }

    public void tradeSettlementFailed() {
        tradeSettlementFailedCounter.increment();
    }

    public void recordTradeSettlementProcessing(Duration duration) {
        tradeSettlementProcessingTimer.record(duration);
    }

    public void recordTradeSettlementSerialization(Duration duration) {
        tradeSettlementSerializationTimer.record(duration);
    }

    public void recordTradeSettlementTransaction(Duration duration) {
        tradeSettlementTransactionTimer.record(duration);
    }

    public void recordTradeSettlementCte(Duration duration) {
        tradeSettlementCteTimer.record(duration);
    }

    private Timer stageTimer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }
}

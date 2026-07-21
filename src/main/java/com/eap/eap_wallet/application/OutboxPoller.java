package com.eap.eap_wallet.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@ConditionalOnProperty(name = "eap.wallet.outbox-relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final WalletMetrics walletMetrics;
    private final int batchSize;
    private final int publishConcurrency;
    private final ExecutorService publishExecutor;
    private final long confirmTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public OutboxPoller(
            OutboxRepository outboxRepository,
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbcTemplate,
            RabbitTemplate rabbitTemplate,
            WalletMetrics walletMetrics,
            @Value("${eap.wallet.outbox-relay.batch-size:200}") int batchSize,
            @Value("${eap.wallet.outbox-relay.publish-concurrency:1}") int publishConcurrency,
            @Value("${eap.wallet.outbox-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${eap.wallet.outbox-relay.max-attempts:10}") int maxAttempts,
            @Value("${eap.wallet.outbox-relay.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.wallet.outbox-relay.max-backoff-ms:300000}") long maxBackoffMs) {
        this.outboxRepository = outboxRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.walletMetrics = walletMetrics;
        this.batchSize = batchSize;
        this.publishConcurrency = Math.max(1, publishConcurrency);
        this.publishExecutor = this.publishConcurrency > 1
                ? Executors.newFixedThreadPool(this.publishConcurrency, new WalletOutboxPublishThreadFactory())
                : null;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @PreDestroy
    public void shutdown() {
        if (publishExecutor != null) {
            publishExecutor.shutdown();
        }
    }

    @Scheduled(fixedDelayString = "${eap.wallet.outbox-relay.poll-interval-ms:500}")
    public void pollAndPublish() {
        boolean continueDraining;
        do {
            Instant batchStartedAt = Instant.now();
            Instant selectStartedAt = Instant.now();
            List<OutboxRow> pending;
            try {
                pending = jdbcTemplate.query("""
                                SELECT id, event_type, routing_key, payload, attempt_count
                                FROM wallet_service.outbox
                                WHERE status = 'PENDING'
                                  AND next_retry_at <= CURRENT_TIMESTAMP
                                ORDER BY created_at, id
                                LIMIT ?
                                """,
                        (rs, rowNum) -> new OutboxRow(
                                rs.getLong("id"),
                                rs.getString("event_type"),
                                rs.getString("routing_key"),
                                rs.getString("payload"),
                                rs.getInt("attempt_count")),
                        batchSize);
            } finally {
                walletMetrics.recordOutboxSelect(Duration.between(selectStartedAt, Instant.now()));
            }
            if (pending.isEmpty()) {
                return;
            }
            boolean batchSucceeded = true;
            List<PublishAttempt> attempts = new ArrayList<>(pending.size());

            List<PublishResult> publishResults = publishBatch(pending);
            for (PublishResult result : publishResults) {
                if (result.succeeded()) {
                    attempts.add(new PublishAttempt(result.entry(), result.correlationData(), result.startedAt()));
                } else {
                    batchSucceeded = false;
                    walletMetrics.outboxPublishFailed();
                    recordFailure(result.entry(), result.failure());
                    walletMetrics.recordOutboxPublish(Duration.between(result.startedAt(), Instant.now()));
                }
            }

            long confirmationDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
            List<PublishAttempt> confirmedAttempts = new ArrayList<>(attempts.size());
            for (PublishAttempt attempt : attempts) {
                OutboxRow entry = attempt.entry();
                Instant confirmStartedAt = Instant.now();
                try {
                    awaitBrokerConfirmation(entry, attempt.correlationData(), confirmationDeadlineNanos);
                    confirmedAttempts.add(attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    walletMetrics.outboxPublishFailed();
                    log.warn("Outbox relay interrupted while waiting for broker confirmation: id={}", entry.id());
                    return;
                } catch (Exception e) {
                    batchSucceeded = false;
                    walletMetrics.outboxPublishFailed();
                    recordFailure(entry, e);
                    walletMetrics.recordOutboxPublish(Duration.between(attempt.startedAt(), Instant.now()));
                } finally {
                    walletMetrics.recordOutboxConfirm(Duration.between(confirmStartedAt, Instant.now()));
                }
            }

            if (!confirmedAttempts.isEmpty()) {
                try {
                    markConfirmedAsSent(confirmedAttempts);
                } catch (Exception e) {
                    batchSucceeded = false;
                    for (PublishAttempt attempt : confirmedAttempts) {
                        walletMetrics.outboxPublishFailed();
                        recordFailure(attempt.entry(), e);
                        walletMetrics.recordOutboxPublish(
                                Duration.between(attempt.startedAt(), Instant.now()));
                    }
                }
            }
            continueDraining = batchSucceeded && pending.size() == batchSize;
            walletMetrics.recordOutboxBatch(Duration.between(batchStartedAt, Instant.now()));
        } while (continueDraining);
    }

    private List<PublishResult> publishBatch(List<OutboxRow> pending) {
        if (publishConcurrency == 1 || pending.size() <= 1) {
            List<PublishResult> results = new ArrayList<>(pending.size());
            try {
                rabbitTemplate.invoke(operations -> {
                    for (OutboxRow entry : pending) {
                        results.add(publishOne(entry, operations));
                    }
                    return null;
                });
            } catch (Exception e) {
                int publishedOrFailed = results.size();
                for (int i = publishedOrFailed; i < pending.size(); i++) {
                    results.add(PublishResult.failure(pending.get(i), Instant.now(), e));
                }
            }
            return results;
        }

        List<List<OutboxRow>> chunks = partition(pending, publishConcurrency);
        List<CompletableFuture<List<PublishResult>>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> publishChunk(chunk), publishExecutor))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();
    }

    private List<PublishResult> publishChunk(List<OutboxRow> chunk) {
        List<PublishResult> results = new ArrayList<>(chunk.size());
        try {
            rabbitTemplate.invoke(operations -> {
                for (OutboxRow entry : chunk) {
                    results.add(publishOne(entry, operations));
                }
                return null;
            });
        } catch (Exception e) {
            int publishedOrFailed = results.size();
            for (int i = publishedOrFailed; i < chunk.size(); i++) {
                results.add(PublishResult.failure(chunk.get(i), Instant.now(), e));
            }
        }
        return results;
    }

    private List<List<OutboxRow>> partition(List<OutboxRow> pending, int maxChunks) {
        int chunkCount = Math.min(maxChunks, pending.size());
        int chunkSize = (int) Math.ceil(pending.size() / (double) chunkCount);
        List<List<OutboxRow>> chunks = new ArrayList<>(chunkCount);
        for (int start = 0; start < pending.size(); start += chunkSize) {
            chunks.add(pending.subList(start, Math.min(start + chunkSize, pending.size())));
        }
        return chunks;
    }

    private PublishResult publishOne(OutboxRow entry, RabbitOperations operations) {
        Instant startedAt = Instant.now();
        Instant enqueueStartedAt = Instant.now();
        try {
            String exchange = resolveExchange(entry.eventType());
            CorrelationData correlationData = new CorrelationData(Long.toString(entry.id()));
            operations.send(exchange, entry.routingKey(), toJsonMessage(entry), correlationData);
            return PublishResult.success(entry, correlationData, startedAt);
        } catch (Exception e) {
            return PublishResult.failure(entry, startedAt, e);
        } finally {
            walletMetrics.recordOutboxPublishEnqueue(Duration.between(enqueueStartedAt, Instant.now()));
        }
    }

    private void recordFailure(OutboxRow entry, Exception failure) {
        int attemptCount = entry.attemptCount() + 1;
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());
        String truncatedError = error.substring(0, Math.min(error.length(), 1000));
        LocalDateTime updatedAt = LocalDateTime.now();

        if (attemptCount >= maxAttempts) {
            log.error("Outbox event permanently failed: id={}, attempts={}, error={}",
                    entry.id(), attemptCount, truncatedError);
            namedJdbcTemplate.update("""
                    UPDATE wallet_service.outbox
                    SET attempt_count = :attemptCount,
                        status = 'FAILED',
                        next_retry_at = NULL,
                        last_error = :lastError,
                        updated_at = :updatedAt
                    WHERE id = :id
                      AND status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("attemptCount", attemptCount)
                    .addValue("lastError", truncatedError)
                    .addValue("updatedAt", updatedAt)
                    .addValue("id", entry.id()));
        } else {
            long backoffMs = calculateBackoffMs(attemptCount);
            LocalDateTime nextRetryAt = updatedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs));
            namedJdbcTemplate.update("""
                    UPDATE wallet_service.outbox
                    SET attempt_count = :attemptCount,
                        status = 'PENDING',
                        next_retry_at = :nextRetryAt,
                        last_error = :lastError,
                        updated_at = :updatedAt
                    WHERE id = :id
                      AND status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("attemptCount", attemptCount)
                    .addValue("nextRetryAt", nextRetryAt)
                    .addValue("lastError", truncatedError)
                    .addValue("updatedAt", updatedAt)
                    .addValue("id", entry.id()));
            walletMetrics.outboxRetryScheduled();
            log.warn("Outbox publish failed; retry scheduled: id={}, attempt={}/{}, backoffMs={}, error={}",
                    entry.id(), attemptCount, maxAttempts, backoffMs, truncatedError);
        }
    }

    private void markConfirmedAsSent(List<PublishAttempt> confirmedAttempts) {
        List<Long> ids = confirmedAttempts.stream()
                .map(attempt -> attempt.entry().id())
                .toList();
        LocalDateTime updatedAt = LocalDateTime.now();
        Instant markStartedAt = Instant.now();
        int marked;
        try {
            marked = namedJdbcTemplate.update("""
                    UPDATE wallet_service.outbox
                    SET status = 'SENT',
                        next_retry_at = NULL,
                        last_error = NULL,
                        updated_at = :updatedAt
                    WHERE id IN (:ids)
                      AND status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("updatedAt", updatedAt)
                    .addValue("ids", ids));
        } finally {
            walletMetrics.recordOutboxMarkSent(Duration.between(markStartedAt, Instant.now()));
        }
        if (marked != ids.size()) {
            throw new IllegalStateException(
                    "Expected to mark " + ids.size() + " outbox records SENT, but updated " + marked);
        }

        Instant completedAt = Instant.now();
        for (PublishAttempt attempt : confirmedAttempts) {
            walletMetrics.outboxPublished();
            walletMetrics.recordOutboxPublish(Duration.between(attempt.startedAt(), completedAt));
            log.debug("Outbox event published: id={}, type={}", attempt.entry().id(), attempt.entry().eventType());
        }
    }

    private long calculateBackoffMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(initialBackoffMs * multiplier, maxBackoffMs);
    }

    private void awaitBrokerConfirmation(
            OutboxRow entry,
            CorrelationData correlationData,
            long confirmationDeadlineNanos) throws Exception {
        long remainingNanos = confirmationDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("RabbitMQ publisher confirm batch timed out");
        }
        CorrelationData.Confirm confirm = correlationData.getFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("RabbitMQ nack: " + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new AmqpException("Unroutable outbox event: id=" + entry.id());
        }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        outboxRepository.deleteByStatusAndCreatedAtBefore("SENT", cutoff);
        log.info("已清理 24 小時前的 SENT outbox 記錄");
    }

    private Message toJsonMessage(OutboxRow entry) {
        switch (entry.eventType()) {
            case "OrderConfirmedEvent":
            case "OrderFailedEvent":
            case "AuctionBidConfirmedEvent":
            case "WalletTradeSettledEvent":
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return new Message(entry.payload().getBytes(StandardCharsets.UTF_8), properties);
            default:
                throw new IllegalArgumentException("Unknown event type: " + entry.eventType());
        }
    }

    private String resolveExchange(String eventType) {
        return switch (eventType) {
            case "AuctionBidConfirmedEvent" -> RabbitMQConstants.AUCTION_EXCHANGE;
            case "WalletTradeSettledEvent" -> RabbitMQConstants.TRADE_EXCHANGE;
            default -> RabbitMQConstants.ORDER_EXCHANGE;
        };
    }

    record OutboxRow(long id, String eventType, String routingKey, String payload, int attemptCount) {
    }

    private record PublishAttempt(
            OutboxRow entry,
            CorrelationData correlationData,
            Instant startedAt) {
    }

    private record PublishResult(
            OutboxRow entry,
            CorrelationData correlationData,
            Instant startedAt,
            Exception failure) {

        static PublishResult success(
                OutboxRow entry,
                CorrelationData correlationData,
                Instant startedAt) {
            return new PublishResult(entry, correlationData, startedAt, null);
        }

        static PublishResult failure(OutboxRow entry, Instant startedAt, Exception failure) {
            return new PublishResult(entry, null, startedAt, failure);
        }

        boolean succeeded() {
            return failure == null;
        }
    }

    private static class WalletOutboxPublishThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "wallet-outbox-publisher-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

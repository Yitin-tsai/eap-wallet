package com.eap.eap_wallet.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.AuctionBidConfirmedEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderFailedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "eap.wallet.outbox-relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final WalletMetrics walletMetrics;
    private final int batchSize;
    private final long confirmTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public OutboxPoller(
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            WalletMetrics walletMetrics,
            @Value("${eap.wallet.outbox-relay.batch-size:200}") int batchSize,
            @Value("${eap.wallet.outbox-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${eap.wallet.outbox-relay.max-attempts:10}") int maxAttempts,
            @Value("${eap.wallet.outbox-relay.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.wallet.outbox-relay.max-backoff-ms:300000}") long maxBackoffMs) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.walletMetrics = walletMetrics;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Scheduled(fixedDelayString = "${eap.wallet.outbox-relay.poll-interval-ms:500}")
    public void pollAndPublish() {
        boolean continueDraining;
        do {
            Instant selectStartedAt = Instant.now();
            List<OutboxEntity> pending;
            try {
                pending = outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        "PENDING",
                        LocalDateTime.now(),
                        PageRequest.of(0, batchSize)
                );
            } finally {
                walletMetrics.recordOutboxSelect(Duration.between(selectStartedAt, Instant.now()));
            }
            boolean batchSucceeded = true;
            List<PublishAttempt> attempts = new ArrayList<>(pending.size());

            for (OutboxEntity entry : pending) {
                Instant startedAt = Instant.now();
                try {
                    Object event = deserializeEvent(entry);
                    String exchange = resolveExchange(entry.getEventType());
                    CorrelationData correlationData = new CorrelationData(entry.getId().toString());
                    rabbitTemplate.convertAndSend(
                            exchange,
                            entry.getRoutingKey(),
                            event,
                            correlationData
                    );
                    attempts.add(new PublishAttempt(entry, correlationData, startedAt));
                } catch (Exception e) {
                    batchSucceeded = false;
                    walletMetrics.outboxPublishFailed();
                    recordFailure(entry, e);
                    walletMetrics.recordOutboxPublish(Duration.between(startedAt, Instant.now()));
                }
            }

            long confirmationDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
            List<PublishAttempt> confirmedAttempts = new ArrayList<>(attempts.size());
            for (PublishAttempt attempt : attempts) {
                OutboxEntity entry = attempt.entry();
                try {
                    awaitBrokerConfirmation(entry, attempt.correlationData(), confirmationDeadlineNanos);
                    confirmedAttempts.add(attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    walletMetrics.outboxPublishFailed();
                    log.warn("Outbox relay interrupted while waiting for broker confirmation: id={}", entry.getId());
                    return;
                } catch (Exception e) {
                    batchSucceeded = false;
                    walletMetrics.outboxPublishFailed();
                    recordFailure(entry, e);
                    walletMetrics.recordOutboxPublish(Duration.between(attempt.startedAt(), Instant.now()));
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
        } while (continueDraining);
    }

    private void recordFailure(OutboxEntity entry, Exception failure) {
        int attemptCount = entry.getAttemptCount() + 1;
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());

        entry.setAttemptCount(attemptCount);
        entry.setLastError(error.substring(0, Math.min(error.length(), 1000)));
        entry.setUpdatedAt(LocalDateTime.now());

        if (attemptCount >= maxAttempts) {
            entry.setStatus("FAILED");
            entry.setNextRetryAt(null);
            log.error("Outbox event permanently failed: id={}, attempts={}, error={}",
                    entry.getId(), attemptCount, entry.getLastError());
        } else {
            long backoffMs = calculateBackoffMs(attemptCount);
            entry.setNextRetryAt(LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs)));
            walletMetrics.outboxRetryScheduled();
            log.warn("Outbox publish failed; retry scheduled: id={}, attempt={}/{}, backoffMs={}, error={}",
                    entry.getId(), attemptCount, maxAttempts, backoffMs, entry.getLastError());
        }
        outboxRepository.save(entry);
    }

    private void markConfirmedAsSent(List<PublishAttempt> confirmedAttempts) {
        List<Long> ids = confirmedAttempts.stream()
                .map(attempt -> attempt.entry().getId())
                .toList();
        LocalDateTime updatedAt = LocalDateTime.now();
        Instant markStartedAt = Instant.now();
        int marked;
        try {
            marked = outboxRepository.markPendingAsSent(ids, updatedAt);
        } finally {
            walletMetrics.recordOutboxMarkSent(Duration.between(markStartedAt, Instant.now()));
        }
        if (marked != ids.size()) {
            throw new IllegalStateException(
                    "Expected to mark " + ids.size() + " outbox records SENT, but updated " + marked);
        }

        Instant completedAt = Instant.now();
        for (PublishAttempt attempt : confirmedAttempts) {
            OutboxEntity entry = attempt.entry();
            entry.setStatus("SENT");
            entry.setNextRetryAt(null);
            entry.setLastError(null);
            entry.setUpdatedAt(updatedAt);
            walletMetrics.outboxPublished();
            walletMetrics.recordOutboxPublish(Duration.between(attempt.startedAt(), completedAt));
            log.debug("Outbox 事件已發布: id={}, type={}", entry.getId(), entry.getEventType());
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
            OutboxEntity entry,
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
            throw new AmqpException("Unroutable outbox event: id=" + entry.getId());
        }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        outboxRepository.deleteByStatusAndCreatedAtBefore("SENT", cutoff);
        log.info("已清理 24 小時前的 SENT outbox 記錄");
    }

    private Object deserializeEvent(OutboxEntity entry) throws Exception {
        return switch (entry.getEventType()) {
            case "OrderConfirmedEvent" -> objectMapper.readValue(entry.getPayload(), OrderConfirmedEvent.class);
            case "OrderFailedEvent" -> objectMapper.readValue(entry.getPayload(), OrderFailedEvent.class);
            case "AuctionBidConfirmedEvent" -> objectMapper.readValue(entry.getPayload(), AuctionBidConfirmedEvent.class);
            case "WalletTradeSettledEvent" -> objectMapper.readValue(entry.getPayload(), WalletTradeSettledEvent.class);
            default -> throw new IllegalArgumentException("Unknown event type: " + entry.getEventType());
        };
    }

    private String resolveExchange(String eventType) {
        return switch (eventType) {
            case "AuctionBidConfirmedEvent" -> RabbitMQConstants.AUCTION_EXCHANGE;
            case "WalletTradeSettledEvent" -> RabbitMQConstants.TRADE_EXCHANGE;
            default -> RabbitMQConstants.ORDER_EXCHANGE;
        };
    }

    private record PublishAttempt(
            OutboxEntity entry,
            CorrelationData correlationData,
            Instant startedAt) {
    }
}

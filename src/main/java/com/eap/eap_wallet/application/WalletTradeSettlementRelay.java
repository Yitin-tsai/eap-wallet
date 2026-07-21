package com.eap.eap_wallet.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.WalletTradeSettledEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.wallet.trade-settlement-relay.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WalletTradeSettlementRelay {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final WalletMetrics walletMetrics;
    private final int batchSize;
    private final long confirmTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public WalletTradeSettlementRelay(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbcTemplate,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            WalletMetrics walletMetrics,
            @Value("${eap.wallet.trade-settlement-relay.batch-size:500}") int batchSize,
            @Value("${eap.wallet.trade-settlement-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${eap.wallet.trade-settlement-relay.max-attempts:10}") int maxAttempts,
            @Value("${eap.wallet.trade-settlement-relay.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.wallet.trade-settlement-relay.max-backoff-ms:300000}") long maxBackoffMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.walletMetrics = walletMetrics;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @PreDestroy
    public void shutdown() {
        // Kept for symmetry with the regular outbox relay; no executor is owned here.
    }

    @Scheduled(fixedDelayString = "${eap.wallet.trade-settlement-relay.poll-interval-ms:100}")
    public void pollAndPublish() {
        boolean continueDraining;
        do {
            Instant batchStartedAt = Instant.now();
            List<SettlementRelayRow> pending = selectPending();
            if (pending.isEmpty()) {
                return;
            }

            List<PublishAttempt> attempts = publishBatch(pending);
            long confirmationDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
            List<PublishAttempt> confirmedAttempts = new ArrayList<>(attempts.size());
            boolean batchSucceeded = true;
            for (PublishAttempt attempt : attempts) {
                Instant confirmStartedAt = Instant.now();
                try {
                    awaitBrokerConfirmation(attempt.row(), attempt.correlationData(), confirmationDeadlineNanos);
                    confirmedAttempts.add(attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    walletMetrics.tradeSettlementRelayPublishFailed();
                    log.warn("WalletTradeSettled relay interrupted while waiting for broker confirmation: tradeId={}",
                            attempt.row().tradeId());
                    return;
                } catch (Exception e) {
                    batchSucceeded = false;
                    walletMetrics.tradeSettlementRelayPublishFailed();
                    recordFailure(attempt.row(), e);
                } finally {
                    walletMetrics.recordTradeSettlementRelayConfirm(Duration.between(confirmStartedAt, Instant.now()));
                }
            }

            if (!confirmedAttempts.isEmpty()) {
                try {
                    markConfirmedAsSent(confirmedAttempts);
                } catch (Exception e) {
                    batchSucceeded = false;
                    for (PublishAttempt attempt : confirmedAttempts) {
                        walletMetrics.tradeSettlementRelayPublishFailed();
                        recordFailure(attempt.row(), e);
                    }
                }
            }

            continueDraining = batchSucceeded && pending.size() == batchSize;
            walletMetrics.recordTradeSettlementRelayBatch(Duration.between(batchStartedAt, Instant.now()));
        } while (continueDraining);
    }

    private List<SettlementRelayRow> selectPending() {
        Instant selectStartedAt = Instant.now();
        try {
            return jdbcTemplate.query("""
                            SELECT trade_id, legacy_match_id, settled_at,
                                   buyer_id, seller_id, buyer_order_id, seller_order_id,
                                   deal_price, quantity, buyer_locked_currency,
                                   buyer_refund_currency, seller_received_currency,
                                   attempt_count
                            FROM wallet_service.trade_settlements
                            WHERE event_status = 'PENDING'
                              AND next_retry_at <= CURRENT_TIMESTAMP
                            ORDER BY settled_at, trade_id
                            LIMIT ?
                            """,
                    (rs, rowNum) -> new SettlementRelayRow(
                            rs.getString("trade_id"),
                            (Integer) rs.getObject("legacy_match_id"),
                            rs.getTimestamp("settled_at").toLocalDateTime(),
                            rs.getObject("buyer_id", UUID.class),
                            rs.getObject("seller_id", UUID.class),
                            rs.getObject("buyer_order_id", UUID.class),
                            rs.getObject("seller_order_id", UUID.class),
                            rs.getInt("deal_price"),
                            rs.getInt("quantity"),
                            rs.getInt("buyer_locked_currency"),
                            rs.getInt("buyer_refund_currency"),
                            rs.getInt("seller_received_currency"),
                            rs.getInt("attempt_count")),
                    batchSize);
        } finally {
            walletMetrics.recordTradeSettlementRelaySelect(Duration.between(selectStartedAt, Instant.now()));
        }
    }

    private List<PublishAttempt> publishBatch(List<SettlementRelayRow> pending) {
        List<PublishAttempt> attempts = new ArrayList<>(pending.size());
        rabbitTemplate.invoke(operations -> {
            for (SettlementRelayRow row : pending) {
                attempts.add(publishOne(row, operations));
            }
            return null;
        });
        return attempts;
    }

    private PublishAttempt publishOne(SettlementRelayRow row, RabbitOperations operations) {
        Instant enqueueStartedAt = Instant.now();
        try {
            CorrelationData correlationData = new CorrelationData(row.tradeId());
            operations.send(
                    RabbitMQConstants.TRADE_EXCHANGE,
                    RabbitMQConstants.TRADE_WALLET_SETTLED_KEY,
                    toJsonMessage(row),
                    correlationData);
            return new PublishAttempt(row, correlationData);
        } finally {
            walletMetrics.recordTradeSettlementRelayPublishEnqueue(
                    Duration.between(enqueueStartedAt, Instant.now()));
        }
    }

    private Message toJsonMessage(SettlementRelayRow row) {
        WalletTradeSettledEvent event = WalletTradeSettledEvent.builder()
                .tradeId(row.tradeId())
                .legacyMatchId(row.legacyMatchId())
                .buyerId(row.buyerId())
                .sellerId(row.sellerId())
                .buyerOrderId(row.buyerOrderId())
                .sellerOrderId(row.sellerOrderId())
                .dealPrice(row.dealPrice())
                .quantity(row.quantity())
                .buyerLockedCurrency(row.buyerLockedCurrency())
                .buyerRefundCurrency(row.buyerRefundCurrency())
                .sellerReceivedCurrency(row.sellerReceivedCurrency())
                .settledAt(row.settledAt())
                .build();
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return new Message(objectMapper.writeValueAsBytes(event), properties);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WalletTradeSettledEvent: tradeId="
                    + row.tradeId(), e);
        }
    }

    private void awaitBrokerConfirmation(
            SettlementRelayRow row,
            CorrelationData correlationData,
            long confirmationDeadlineNanos) throws Exception {
        long remainingNanos = confirmationDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("WalletTradeSettled relay confirm batch timed out");
        }
        CorrelationData.Confirm confirm = correlationData.getFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("RabbitMQ nack: " + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new AmqpException("Unroutable WalletTradeSettled event: tradeId=" + row.tradeId());
        }
    }

    private void markConfirmedAsSent(List<PublishAttempt> confirmedAttempts) {
        List<String> tradeIds = confirmedAttempts.stream()
                .map(attempt -> attempt.row().tradeId())
                .toList();
        LocalDateTime updatedAt = LocalDateTime.now();
        Instant markStartedAt = Instant.now();
        int marked;
        try {
            marked = namedJdbcTemplate.update("""
                    UPDATE wallet_service.trade_settlements
                    SET event_status = 'SENT',
                        next_retry_at = NULL,
                        last_error = NULL,
                        updated_at = :updatedAt
                    WHERE trade_id IN (:tradeIds)
                      AND event_status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("updatedAt", updatedAt)
                    .addValue("tradeIds", tradeIds));
        } finally {
            walletMetrics.recordTradeSettlementRelayMarkSent(Duration.between(markStartedAt, Instant.now()));
        }
        if (marked != tradeIds.size()) {
            throw new IllegalStateException(
                    "Expected to mark " + tradeIds.size() + " WalletTradeSettled records SENT, but updated " + marked);
        }
        for (int i = 0; i < confirmedAttempts.size(); i++) {
            walletMetrics.tradeSettlementRelayPublished();
        }
    }

    private void recordFailure(SettlementRelayRow row, Exception failure) {
        int attemptCount = row.attemptCount() + 1;
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());
        String truncatedError = error.substring(0, Math.min(error.length(), 1000));
        LocalDateTime updatedAt = LocalDateTime.now();

        if (attemptCount >= maxAttempts) {
            namedJdbcTemplate.update("""
                    UPDATE wallet_service.trade_settlements
                    SET attempt_count = :attemptCount,
                        event_status = 'FAILED',
                        next_retry_at = NULL,
                        last_error = :lastError,
                        updated_at = :updatedAt
                    WHERE trade_id = :tradeId
                      AND event_status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("attemptCount", attemptCount)
                    .addValue("lastError", truncatedError)
                    .addValue("updatedAt", updatedAt)
                    .addValue("tradeId", row.tradeId()));
            log.error("WalletTradeSettled relay permanently failed: tradeId={}, attempts={}, error={}",
                    row.tradeId(), attemptCount, truncatedError);
            return;
        }

        long backoffMs = calculateBackoffMs(attemptCount);
        LocalDateTime nextRetryAt = updatedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs));
        namedJdbcTemplate.update("""
                UPDATE wallet_service.trade_settlements
                SET attempt_count = :attemptCount,
                    event_status = 'PENDING',
                    next_retry_at = :nextRetryAt,
                    last_error = :lastError,
                    updated_at = :updatedAt
                WHERE trade_id = :tradeId
                  AND event_status = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("attemptCount", attemptCount)
                .addValue("nextRetryAt", nextRetryAt)
                .addValue("lastError", truncatedError)
                .addValue("updatedAt", updatedAt)
                .addValue("tradeId", row.tradeId()));
        walletMetrics.tradeSettlementRelayRetryScheduled();
        log.warn("WalletTradeSettled relay retry scheduled: tradeId={}, attempt={}/{}, backoffMs={}, error={}",
                row.tradeId(), attemptCount, maxAttempts, backoffMs, truncatedError);
    }

    private long calculateBackoffMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(initialBackoffMs * multiplier, maxBackoffMs);
    }

    record SettlementRelayRow(
            String tradeId,
            Integer legacyMatchId,
            LocalDateTime settledAt,
            UUID buyerId,
            UUID sellerId,
            UUID buyerOrderId,
            UUID sellerOrderId,
            int dealPrice,
            int quantity,
            int buyerLockedCurrency,
            int buyerRefundCurrency,
            int sellerReceivedCurrency,
            int attemptCount) {
    }

    private record PublishAttempt(SettlementRelayRow row, CorrelationData correlationData) {
    }
}

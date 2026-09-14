package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderSubmittedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.eap.common.event.TradeExecutedEvent.MAX_TRADE_ID_LENGTH;

@Component
@RequiredArgsConstructor
public class WalletMessageInbox {

    private static final int ERROR_LIMIT = 2_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReceiveOutcome receiveOrderSubmitted(OrderSubmittedEvent event) {
        if (event == null || event.getOrderId() == null || event.getUserId() == null) {
            throw new IllegalArgumentException("OrderSubmittedEvent orderId and userId are required");
        }
        return receive(MessageType.ORDER_SUBMITTED, event.getOrderId().toString(), event);
    }

    public ReceiveOutcome receiveCancellationResult(OrderCancellationResultEvent event) {
        if (event == null || event.getCancellationId() == null
                || event.getOrderId() == null || event.getUserId() == null
                || event.getOutcome() == null) {
            throw new IllegalArgumentException("Cancellation result identifiers and outcome are required");
        }
        return receive(MessageType.ORDER_CANCELLATION_RESULT, event.getCancellationId().toString(), event);
    }

    public ReceiveOutcome receiveTradeExecuted(TradeExecutedEvent event) {
        if (event == null || event.getTradeId() == null || event.getTradeId().isBlank()) {
            throw new IllegalArgumentException("TradeExecutedEvent tradeId is required");
        }
        if (event.getTradeId().length() > MAX_TRADE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "TradeExecutedEvent tradeId exceeds " + MAX_TRADE_ID_LENGTH + " characters");
        }
        return receive(MessageType.TRADE_EXECUTED, event.getTradeId(), event);
    }

    private ReceiveOutcome receive(MessageType type, String messageId, Object event) {
        String payload = serialize(event);
        String hash = sha256(payload);
        int inserted = jdbc.update("""
                INSERT INTO wallet_service.message_inbox
                    (message_type, message_id, payload, payload_hash, schema_version,
                     status, attempt_count, next_retry_at, received_at, updated_at)
                VALUES
                    (:messageType, :messageId, :payload, :payloadHash, 1,
                     'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (message_type, message_id) DO NOTHING
                """, params(type, messageId)
                .addValue("payload", payload)
                .addValue("payloadHash", hash));
        if (inserted == 1) {
            return ReceiveOutcome.ACCEPTED;
        }

        String existingHash = jdbc.queryForObject("""
                SELECT payload_hash
                FROM wallet_service.message_inbox
                WHERE message_type = :messageType AND message_id = :messageId
                """, params(type, messageId), String.class);
        if (hash.equals(existingHash)) {
            return ReceiveOutcome.DUPLICATE;
        }

        jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET status = CASE WHEN status = 'APPLIED' THEN status ELSE 'FAILED_PERMANENT' END,
                    error_type = 'IDENTITY_CONFLICT',
                    last_error = :lastError,
                    conflict_detected_at = CURRENT_TIMESTAMP,
                    conflicting_payload = :payload,
                    claimed_by = CASE WHEN status = 'APPLIED' THEN claimed_by ELSE NULL END,
                    claim_until = CASE WHEN status = 'APPLIED' THEN claim_until ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE message_type = :messageType AND message_id = :messageId
                """, params(type, messageId)
                .addValue("payload", payload)
                .addValue("lastError", "Wallet inbox identity conflict: type=" + type + ", id=" + messageId));
        return ReceiveOutcome.CONFLICT;
    }

    public List<InboxEntry> claimRetryable(int limit, String owner, long leaseMs) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT message_type, message_id
                    FROM wallet_service.message_inbox
                    WHERE (status IN ('PENDING', 'FAILED_RETRYABLE')
                               AND next_retry_at <= CURRENT_TIMESTAMP)
                       OR (status = 'IN_PROGRESS' AND claim_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_retry_at, updated_at, message_type, message_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE wallet_service.message_inbox AS inbox
                SET status = 'IN_PROGRESS',
                    attempt_count = inbox.attempt_count + 1,
                    claimed_by = :owner,
                    claim_until = CURRENT_TIMESTAMP + (:leaseMs * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE inbox.message_type = candidates.message_type
                  AND inbox.message_id = candidates.message_id
                RETURNING inbox.message_type, inbox.message_id, inbox.payload,
                          inbox.payload_hash, inbox.attempt_count
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("owner", owner)
                .addValue("leaseMs", leaseMs),
                (rs, rowNum) -> new InboxEntry(
                        MessageType.valueOf(rs.getString("message_type")),
                        rs.getString("message_id"),
                        rs.getString("payload"),
                        rs.getString("payload_hash"),
                        rs.getInt("attempt_count")));
    }

    public boolean markApplied(InboxEntry entry, String owner) {
        return jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET status = 'APPLIED', applied_at = CURRENT_TIMESTAMP,
                    claimed_by = NULL, claim_until = NULL,
                    error_type = NULL, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE message_type = :messageType AND message_id = :messageId
                  AND status = 'IN_PROGRESS' AND claimed_by = :owner
                """, claimParams(entry, owner)) == 1;
    }

    public boolean reschedule(
            InboxEntry entry,
            String owner,
            String status,
            String errorType,
            Exception failure,
            long delayMs) {
        return jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET status = :status,
                    next_retry_at = CURRENT_TIMESTAMP + (:delayMs * INTERVAL '1 millisecond'),
                    claimed_by = NULL, claim_until = NULL,
                    error_type = :errorType, last_error = :lastError,
                    updated_at = CURRENT_TIMESTAMP
                WHERE message_type = :messageType AND message_id = :messageId
                  AND status = 'IN_PROGRESS' AND claimed_by = :owner
                """, claimParams(entry, owner)
                .addValue("status", status)
                .addValue("delayMs", delayMs)
                .addValue("errorType", errorType)
                .addValue("lastError", truncate(failure.toString()))) == 1;
    }

    public boolean markPermanent(
            InboxEntry entry,
            String owner,
            String errorType,
            Exception failure) {
        return jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET status = 'FAILED_PERMANENT',
                    claimed_by = NULL, claim_until = NULL,
                    error_type = :errorType, last_error = :lastError,
                    updated_at = CURRENT_TIMESTAMP
                WHERE message_type = :messageType AND message_id = :messageId
                  AND status = 'IN_PROGRESS' AND claimed_by = :owner
                """, claimParams(entry, owner)
                .addValue("errorType", errorType)
                .addValue("lastError", truncate(failure.toString()))) == 1;
    }

    public Map<String, Long> countByStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS rows
                FROM wallet_service.message_inbox
                GROUP BY status
                """, rs -> {
                    Map<String, Long> counts = new java.util.HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("status"), rs.getLong("rows"));
                    }
                    return Map.copyOf(counts);
                });
    }

    public long countIdentityConflicts() {
        Long count = jdbc.getJdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM wallet_service.message_inbox
                WHERE conflict_detected_at IS NOT NULL
                """, Long.class);
        return count == null ? 0L : count;
    }

    public long oldestUnresolvedAgeSeconds() {
        Long age = jdbc.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(
                    EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(received_at)))::BIGINT,
                    0)
                FROM wallet_service.message_inbox
                WHERE status <> 'APPLIED'
                """, Long.class);
        return age == null ? 0L : Math.max(age, 0L);
    }

    private MapSqlParameterSource params(MessageType type, String id) {
        return new MapSqlParameterSource()
                .addValue("messageType", type.name())
                .addValue("messageId", id);
    }

    private MapSqlParameterSource claimParams(InboxEntry entry, String owner) {
        return params(entry.messageType(), entry.messageId()).addValue("owner", owner);
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize Wallet inbox message", e);
        }
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String truncate(String value) {
        return value == null || value.length() <= ERROR_LIMIT
                ? value
                : value.substring(0, ERROR_LIMIT);
    }

    public enum MessageType {
        ORDER_SUBMITTED,
        ORDER_CANCELLATION_RESULT,
        TRADE_EXECUTED
    }

    public enum ReceiveOutcome {
        ACCEPTED,
        DUPLICATE,
        CONFLICT
    }

    public record InboxEntry(
            MessageType messageType,
            String messageId,
            String payload,
            String payloadHash,
            int attemptCount) {
    }
}

package com.eap.eap_wallet.application;

import com.eap.eap_wallet.domain.dto.WalletInboxMessageView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WalletInboxInspectionService {

    private static final int MAX_LIST_LIMIT = 100;
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "IN_PROGRESS", "APPLIED", "FAILED_RETRYABLE", "FAILED_PERMANENT");

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<WalletInboxMessageView> list(
            String requestedStatus,
            String requestedMessageType,
            int requestedLimit) {
        String status = normalizeStatus(requestedStatus);
        String messageType = normalizeMessageType(requestedMessageType);
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIST_LIMIT);

        StringBuilder sql = new StringBuilder("""
                SELECT message_type, message_id, status, attempt_count,
                       next_retry_at, claimed_by, claim_until, received_at, applied_at,
                       error_type, last_error, conflict_detected_at, updated_at
                FROM wallet_service.message_inbox
                WHERE 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        if (status != null) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        if (messageType != null) {
            sql.append(" AND message_type = :messageType");
            params.addValue("messageType", messageType);
        }
        sql.append("""
                 ORDER BY CASE WHEN status = 'APPLIED' THEN 1 ELSE 0 END,
                          received_at, message_type, message_id
                 LIMIT :limit
                """);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new WalletInboxMessageView(
                rs.getString("message_type"),
                rs.getString("message_id"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getTimestamp("next_retry_at").toLocalDateTime(),
                rs.getString("claimed_by"),
                nullableTimestamp(rs, "claim_until"),
                rs.getTimestamp("received_at").toLocalDateTime(),
                nullableTimestamp(rs, "applied_at"),
                rs.getString("error_type"),
                rs.getString("last_error"),
                nullableTimestamp(rs, "conflict_detected_at"),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private String normalizeStatus(String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return null;
        }
        String status = requestedStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported Wallet inbox status: " + requestedStatus);
        }
        return status;
    }

    private String normalizeMessageType(String requestedMessageType) {
        if (requestedMessageType == null || requestedMessageType.isBlank()) {
            return null;
        }
        String messageType = requestedMessageType.trim().toUpperCase(Locale.ROOT);
        try {
            return WalletMessageInbox.MessageType.valueOf(messageType).name();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Unsupported Wallet inbox message type: " + requestedMessageType, failure);
        }
    }

    private static java.time.LocalDateTime nullableTimestamp(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

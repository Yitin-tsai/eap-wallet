package com.eap.eap_wallet.configuration.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseId;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryDryRunRequest;
import com.eap.common.recovery.RecoveryDryRunResult;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.common.recovery.RecoveryExecutionStatus;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.common.recovery.RecoveryFingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class WalletRecoveryCaseService {

    static final String SERVICE = "eap-wallet";
    static final String ORDER_SUBMITTED = "order_submitted_inbox";
    static final String CANCELLATION_RESULT = "order_cancellation_result_inbox";
    static final String TRADE_EXECUTED = "trade_executed_inbox";
    static final String EVENT_OUTBOX = "event_outbox";

    private static final int MAX_LIMIT = 100;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final Map<String, String> MESSAGE_TYPES = Map.of(
            ORDER_SUBMITTED, "ORDER_SUBMITTED",
            CANCELLATION_RESULT, "ORDER_CANCELLATION_RESULT",
            TRADE_EXECUTED, "TRADE_EXECUTED");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WalletRecoveryCaseService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RecoveryCaseSummary> list(int requestedLimit) {
        int limit = boundedLimit(requestedLimit);
        List<RecoveryCaseDetail> cases = new ArrayList<>();
        MESSAGE_TYPES.keySet().forEach(work -> cases.addAll(queryInbox(work, null, limit)));
        cases.addAll(queryOutbox(null, limit));
        return cases.stream()
                .sorted(Comparator.comparing(detail -> detail.summary().firstSeenAt()))
                .limit(limit)
                .map(RecoveryCaseDetail::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecoveryCaseDetail detail(String caseId) {
        RecoveryCaseId.Parts parts = requireWalletCase(caseId);
        List<RecoveryCaseDetail> cases = EVENT_OUTBOX.equals(parts.work())
                ? queryOutbox(parts.sourceId(), 1)
                : queryInbox(parts.work(), parts.sourceId(), 1);
        return cases.stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Recovery case not found: " + caseId));
    }

    @Transactional(readOnly = true)
    public RecoveryDryRunResult dryRun(String caseId, RecoveryDryRunRequest request) {
        RecoveryCaseDetail detail = detail(caseId);
        boolean fingerprintMatches = detail.summary().fingerprint().equals(request.expectedFingerprint());
        boolean actionAllowed = detail.summary().allowedActions().contains(request.action());
        boolean allowed = fingerprintMatches && actionAllowed;
        String reason = !fingerprintMatches
                ? "Source changed after inspection; refresh the case before acting"
                : actionAllowed
                        ? "Action is allowed by the current service-owned recovery policy"
                        : "Action is not allowed for this failure class";
        return new RecoveryDryRunResult(
                caseId,
                request.action(),
                allowed,
                reason,
                detail.summary().fingerprint(),
                detail.summary());
    }

    @Transactional
    public RecoveryExecuteResult execute(String caseId, RecoveryExecuteRequest request) {
        if (request.action() != RecoveryActionType.REPLAY) {
            throw new IllegalArgumentException("Wallet source only executes REPLAY; PARK/RESOLVE belong to control plane");
        }
        lockAction(request);
        RecoveryExecuteResult existing = storedAction(caseId, request);
        if (existing != null) {
            return existing;
        }
        RecoveryCaseId.Parts parts = requireWalletCase(caseId);
        RecoveryCaseDetail before = detail(caseId);
        RecoveryDryRunResult check = dryRun(caseId,
                new RecoveryDryRunRequest(request.action(), request.expectedFingerprint()));
        if (!check.allowed()) {
            return remember(caseId, request, rejected(request, before, check.reason()));
        }

        lock(parts.work(), parts.sourceId());
        before = detail(caseId);
        check = dryRun(caseId,
                new RecoveryDryRunRequest(request.action(), request.expectedFingerprint()));
        if (!check.allowed()) {
            return remember(caseId, request, rejected(request, before, check.reason()));
        }

        int updated = replay(parts.work(), parts.sourceId());
        if (updated != 1) {
            return remember(caseId, request, new RecoveryExecuteResult(
                    request.actionId(), caseId, request.action(),
                    RecoveryExecutionStatus.NO_LONGER_ELIGIBLE,
                    "Recovery source no longer matches the replay precondition",
                    before.summary(), currentOrNull(caseId)));
        }
        return remember(caseId, request, new RecoveryExecuteResult(
                request.actionId(), caseId, request.action(), RecoveryExecutionStatus.APPLIED,
                "Terminal work was moved back to its service-owned retry state",
                before.summary(), currentOrNull(caseId)));
    }

    private List<RecoveryCaseDetail> queryInbox(String work, String sourceId, int limit) {
        return jdbc.query("""
                SELECT message_id AS source_id, status, attempt_count,
                       COALESCE(LEAST(received_at, conflict_detected_at), received_at) AS first_seen_at,
                       updated_at AS last_updated_at,
                       COALESCE(error_type,
                           CASE WHEN conflict_detected_at IS NOT NULL THEN 'IDENTITY_CONFLICT' END)
                           AS error_type,
                       last_error AS error_summary, payload,
                       json_build_object('messageType', message_type,
                                         'messageId', message_id,
                                         'payloadHash', payload_hash)::text AS identity_json
                FROM wallet_service.message_inbox
                WHERE message_type = :messageType
                  AND (status = 'FAILED_PERMANENT' OR conflict_detected_at IS NOT NULL)
                  AND (CAST(:sourceId AS text) IS NULL OR message_id = :sourceId)
                ORDER BY first_seen_at, message_id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("messageType", MESSAGE_TYPES.get(work))
                .addValue("sourceId", sourceId)
                .addValue("limit", limit),
                (rs, rowNum) -> mapInbox(work, rs));
    }

    private List<RecoveryCaseDetail> queryOutbox(String sourceId, int limit) {
        return jdbc.query("""
                SELECT id::text AS source_id, status, attempt_count,
                       created_at AS first_seen_at, updated_at AS last_updated_at,
                       'PUBLISH_RETRY_EXHAUSTED' AS error_type,
                       last_error AS error_summary, payload,
                       json_build_object('outboxId', id::text,
                                         'eventType', event_type,
                                         'routingKey', routing_key)::text AS identity_json
                FROM wallet_service.outbox
                WHERE status = 'FAILED'
                  AND (CAST(:sourceId AS text) IS NULL OR id::text = :sourceId)
                ORDER BY created_at, id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("limit", limit),
                (rs, rowNum) -> map(EVENT_OUTBOX, RecoveryDebtType.OUTBOX_TERMINAL, rs));
    }

    private RecoveryCaseDetail mapInbox(String work, ResultSet rs) throws SQLException {
        return map(work, RecoveryDebtType.INBOX_TERMINAL, rs);
    }

    private RecoveryCaseDetail map(String work, RecoveryDebtType debtType, ResultSet rs) throws SQLException {
        String sourceId = rs.getString("source_id");
        String status = rs.getString("status");
        String errorType = rs.getString("error_type");
        String payload = rs.getString("payload");
        int attemptCount = rs.getInt("attempt_count");
        Instant firstSeen = rs.getTimestamp("first_seen_at").toInstant();
        Instant updated = rs.getTimestamp("last_updated_at").toInstant();
        RecoveryFailureClass failureClass = classify(errorType, debtType);
        String caseId = RecoveryCaseId.encode(SERVICE, debtType, work, sourceId);
        RecoveryCaseSummary summary = new RecoveryCaseSummary(
                caseId,
                SERVICE,
                debtType,
                work,
                sourceId,
                status,
                failureClass,
                attemptCount,
                firstSeen,
                updated,
                errorType,
                rs.getString("error_summary"),
                readIdentity(rs.getString("identity_json")),
                allowedActions(work, failureClass),
                RecoveryFingerprint.sha256(
                        caseId, status, Integer.toString(attemptCount), errorType, payload, updated.toString()));
        return new RecoveryCaseDetail(summary, payload, Map.of("source", "postgresql"));
    }

    private Set<RecoveryActionType> allowedActions(String work, RecoveryFailureClass failureClass) {
        EnumSet<RecoveryActionType> actions = EnumSet.of(
                RecoveryActionType.PARK, RecoveryActionType.RESOLVE);
        if (EVENT_OUTBOX.equals(work) || failureClass == RecoveryFailureClass.TRANSIENT) {
            actions.add(RecoveryActionType.REPLAY);
        }
        return actions;
    }

    private RecoveryFailureClass classify(String errorType, RecoveryDebtType debtType) {
        if (debtType == RecoveryDebtType.OUTBOX_TERMINAL) {
            return RecoveryFailureClass.TRANSIENT;
        }
        if (errorType == null) {
            return RecoveryFailureClass.UNKNOWN;
        }
        String normalized = errorType.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("RETRY_EXHAUSTED_") || normalized.contains("TRANSIENT")) {
            return RecoveryFailureClass.TRANSIENT;
        }
        if (normalized.contains("IDENTITY") || normalized.contains("CONFLICT")) {
            return RecoveryFailureClass.IDENTITY;
        }
        if (normalized.contains("SCHEMA") || normalized.contains("DESERIAL")
                || normalized.contains("PAYLOAD")) {
            return RecoveryFailureClass.SCHEMA;
        }
        if (normalized.contains("INVARIANT") || normalized.contains("OWNERSHIP")) {
            return RecoveryFailureClass.INVARIANT;
        }
        return RecoveryFailureClass.PERMANENT;
    }

    private RecoveryCaseId.Parts requireWalletCase(String caseId) {
        RecoveryCaseId.Parts parts = RecoveryCaseId.decode(caseId);
        if (!SERVICE.equals(parts.service())) {
            throw new IllegalArgumentException("Recovery case is not owned by Wallet");
        }
        RecoveryDebtType expected = EVENT_OUTBOX.equals(parts.work())
                ? RecoveryDebtType.OUTBOX_TERMINAL
                : RecoveryDebtType.INBOX_TERMINAL;
        if ((!EVENT_OUTBOX.equals(parts.work()) && !MESSAGE_TYPES.containsKey(parts.work()))
                || parts.debtType() != expected) {
            throw new IllegalArgumentException("Unsupported Wallet recovery work: " + parts.work());
        }
        return parts;
    }

    private void lock(String work, String sourceId) {
        String sql;
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("sourceId", sourceId);
        if (EVENT_OUTBOX.equals(work)) {
            sql = "SELECT 1 FROM wallet_service.outbox WHERE id::text = :sourceId FOR UPDATE";
        } else {
            sql = """
                    SELECT 1 FROM wallet_service.message_inbox
                    WHERE message_type = :messageType AND message_id = :sourceId
                    FOR UPDATE
                    """;
            parameters.addValue("messageType", MESSAGE_TYPES.get(work));
        }
        if (jdbc.queryForList(sql, parameters).isEmpty()) {
            throw new NoSuchElementException("Recovery source no longer exists");
        }
    }

    private int replay(String work, String sourceId) {
        if (EVENT_OUTBOX.equals(work)) {
            return jdbc.update("""
                    UPDATE wallet_service.outbox
                    SET status = 'PENDING', attempt_count = 0,
                        next_retry_at = CURRENT_TIMESTAMP, last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id::text = :sourceId AND status = 'FAILED'
                    """, Map.of("sourceId", sourceId));
        }
        return jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET status = 'FAILED_RETRYABLE', next_retry_at = CURRENT_TIMESTAMP,
                    claimed_by = NULL, claim_until = NULL,
                    error_type = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE message_type = :messageType AND message_id = :sourceId
                  AND status = 'FAILED_PERMANENT'
                  AND conflict_detected_at IS NULL
                  AND error_type LIKE 'RETRY_EXHAUSTED_%'
                """, new MapSqlParameterSource()
                .addValue("messageType", MESSAGE_TYPES.get(work))
                .addValue("sourceId", sourceId));
    }

    private RecoveryExecuteResult rejected(
            RecoveryExecuteRequest request,
            RecoveryCaseDetail before,
            String reason) {
        return new RecoveryExecuteResult(
                request.actionId(), before.summary().caseId(), request.action(),
                RecoveryExecutionStatus.REJECTED, reason, before.summary(), before.summary());
    }

    private RecoveryCaseSummary currentOrNull(String caseId) {
        try {
            return detail(caseId).summary();
        } catch (NoSuchElementException resolved) {
            return null;
        }
    }

    private void lockAction(RecoveryExecuteRequest request) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:actionId))",
                Map.of("actionId", request.actionId().toString()), rs -> {
                    rs.next();
                    return Boolean.TRUE;
                });
    }

    private RecoveryExecuteResult storedAction(String caseId, RecoveryExecuteRequest request) {
        return jdbc.query("""
                SELECT case_id, action_type, expected_fingerprint, result_json
                FROM wallet_service.recovery_source_actions
                WHERE action_id = :actionId
                """, Map.of("actionId", request.actionId()), rs -> {
            if (!rs.next()) {
                return null;
            }
            if (!caseId.equals(rs.getString("case_id"))
                    || !request.action().name().equals(rs.getString("action_type"))
                    || !request.expectedFingerprint().equals(rs.getString("expected_fingerprint"))) {
                throw new IllegalArgumentException("Recovery actionId was already used for another request");
            }
            try {
                return objectMapper.readValue(rs.getString("result_json"), RecoveryExecuteResult.class);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot deserialize stored recovery result", failure);
            }
        });
    }

    private RecoveryExecuteResult remember(
            String caseId,
            RecoveryExecuteRequest request,
            RecoveryExecuteResult result) {
        try {
            jdbc.update("""
                    INSERT INTO wallet_service.recovery_source_actions
                        (action_id, case_id, action_type, expected_fingerprint, result_json)
                    VALUES (:actionId, :caseId, :actionType, :fingerprint, :resultJson)
                    """, new MapSqlParameterSource()
                    .addValue("actionId", request.actionId())
                    .addValue("caseId", caseId)
                    .addValue("actionType", request.action().name())
                    .addValue("fingerprint", request.expectedFingerprint())
                    .addValue("resultJson", objectMapper.writeValueAsString(result)));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize recovery result", failure);
        }
    }

    private Map<String, String> readIdentity(String json) {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot deserialize recovery identity", failure);
        }
    }

    private int boundedLimit(int requestedLimit) {
        return Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
    }
}

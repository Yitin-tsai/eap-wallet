package com.eap.eap_wallet.configuration.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "eap.wallet.inbox-reconciler.initial-delay-ms=3600000"
        })
class WalletRecoveryCaseServicePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.6"));

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WalletRecoveryCaseService service;
    @Autowired JdbcTemplate jdbc;

    private String messageId;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM wallet_service.recovery_source_actions");
        if (messageId != null) {
            jdbc.update("DELETE FROM wallet_service.message_inbox WHERE message_id = ?", messageId);
        }
    }

    @Test
    void identityConflictShouldBeVisibleButNeverReplayable() {
        messageId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO wallet_service.message_inbox
                    (message_type, message_id, payload, payload_hash, status,
                     attempt_count, error_type, last_error, conflict_detected_at)
                VALUES ('TRADE_EXECUTED', ?, '{}', 'hash', 'FAILED_PERMANENT',
                        1, 'IDENTITY_CONFLICT', 'same trade id with changed payload', CURRENT_TIMESTAMP)
                """, messageId);
        var terminal = service.list(10).stream()
                .filter(item -> item.sourceId().equals(messageId))
                .findFirst().orElseThrow();
        UUID actionId = UUID.randomUUID();

        var result = service.execute(terminal.caseId(), new RecoveryExecuteRequest(
                actionId, RecoveryActionType.REPLAY, terminal.fingerprint()));
        var duplicate = service.execute(terminal.caseId(), new RecoveryExecuteRequest(
                actionId, RecoveryActionType.REPLAY, terminal.fingerprint()));

        assertThat(result.status()).isEqualTo(RecoveryExecutionStatus.REJECTED);
        assertThat(duplicate).isEqualTo(result);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM wallet_service.message_inbox
                WHERE message_type = 'TRADE_EXECUTED' AND message_id = ?
                """, String.class, messageId)).isEqualTo("FAILED_PERMANENT");
    }

    @Test
    void transientTerminalWorkShouldReplayOnceAndReturnStoredResultAfterResponseLoss() {
        messageId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO wallet_service.message_inbox
                    (message_type, message_id, payload, payload_hash, status,
                     attempt_count, error_type, last_error)
                VALUES ('TRADE_EXECUTED', ?, '{}', 'hash', 'FAILED_PERMANENT',
                        20, 'RETRY_EXHAUSTED_TRANSIENT_DATA_STORE', 'database unavailable')
                """, messageId);
        var terminal = service.list(10).stream()
                .filter(item -> item.sourceId().equals(messageId))
                .findFirst().orElseThrow();
        UUID actionId = UUID.randomUUID();
        RecoveryExecuteRequest request = new RecoveryExecuteRequest(
                actionId, RecoveryActionType.REPLAY, terminal.fingerprint());

        var first = service.execute(terminal.caseId(), request);
        var repeatedAfterLostResponse = service.execute(terminal.caseId(), request);

        assertThat(first.status()).isEqualTo(RecoveryExecutionStatus.APPLIED);
        assertThat(repeatedAfterLostResponse).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM wallet_service.message_inbox
                WHERE message_type = 'TRADE_EXECUTED' AND message_id = ?
                """, String.class, messageId)).isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wallet_service.recovery_source_actions
                WHERE action_id = ?
                """, Integer.class, actionId)).isEqualTo(1);
    }
}

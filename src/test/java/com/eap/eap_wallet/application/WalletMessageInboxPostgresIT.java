package com.eap.eap_wallet.application;

import com.eap.common.event.OrderSubmittedEvent;
import com.eap.common.observability.DurableDebtSnapshot;
import com.eap.eap_wallet.configuration.observability.WalletDurableDebtSnapshotProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "eap.wallet.inbox-reconciler.initial-delay-ms=600000"
})
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
class WalletMessageInboxPostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "eap.integration.postgres.url", "jdbc:postgresql://localhost:15433/eap_wallet_db"));
        registry.add("spring.datasource.username", () -> System.getProperty("eap.integration.postgres.user", "admin"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "eap.integration.postgres.password", "admin123"));
    }

    @Autowired WalletMessageInbox inbox;
    @Autowired WalletInboxInspectionService inspectionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired WalletDurableDebtSnapshotProvider durableDebt;

    private UUID orderId;

    @AfterEach
    void cleanup() {
        if (orderId != null) {
            jdbc.update("DELETE FROM wallet_service.message_inbox WHERE message_id = ?", orderId.toString());
        }
    }

    @Test
    void identicalDuplicate_shouldNotCreateAnotherRow_butChangedPayloadShouldBeDurableConflict() {
        OrderSubmittedEvent event = event(5);

        assertEquals(WalletMessageInbox.ReceiveOutcome.ACCEPTED, inbox.receiveOrderSubmitted(event));
        assertEquals(WalletMessageInbox.ReceiveOutcome.DUPLICATE, inbox.receiveOrderSubmitted(event));
        assertEquals(WalletMessageInbox.ReceiveOutcome.CONFLICT, inbox.receiveOrderSubmitted(event(6)));

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_service.message_inbox
                WHERE message_type = 'ORDER_SUBMITTED' AND message_id = ?
                """, Integer.class, orderId.toString()));
        assertEquals("FAILED_PERMANENT", jdbc.queryForObject("""
                SELECT status FROM wallet_service.message_inbox
                WHERE message_type = 'ORDER_SUBMITTED' AND message_id = ?
                """, String.class, orderId.toString()));
    }

    @Test
    void expiredLease_shouldBeReclaimedByAnotherWorker() {
        OrderSubmittedEvent event = event(5);
        inbox.receiveOrderSubmitted(event);
        List<WalletMessageInbox.InboxEntry> first = inbox.claimRetryable(1, "worker-a", 30_000);
        assertEquals(1, first.size());
        jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE message_type = 'ORDER_SUBMITTED' AND message_id = ?
                """, orderId.toString());

        List<WalletMessageInbox.InboxEntry> reclaimed = inbox.claimRetryable(1, "worker-b", 30_000);

        assertEquals(1, reclaimed.size());
        assertEquals(2, reclaimed.get(0).attemptCount());
    }

    @Test
    void unresolvedAgeAndInspection_shouldExposeAttemptAndErrorWithoutPayload() {
        inbox.receiveOrderSubmitted(event(5));
        jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET status = 'FAILED_RETRYABLE', attempt_count = 4,
                    error_type = 'TRANSIENT_DATA_STORE', last_error = 'database unavailable',
                    received_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                WHERE message_type = 'ORDER_SUBMITTED' AND message_id = ?
                """, orderId.toString());

        var rows = inspectionService.list("FAILED_RETRYABLE", "ORDER_SUBMITTED", 10);

        assertEquals(1, rows.size());
        assertEquals(orderId.toString(), rows.get(0).messageId());
        assertEquals(4, rows.get(0).attemptCount());
        assertEquals("TRANSIENT_DATA_STORE", rows.get(0).errorType());
        assertEquals("database unavailable", rows.get(0).lastError());
        assertTrue(inbox.oldestUnresolvedAgeSeconds() >= 119L);

        ReflectionTestUtils.invokeMethod(durableDebt, "refresh");
        var component = durableDebt.snapshot().components().stream()
                .filter(debt -> debt.work().equals("order_submission_inbox"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, component.totalCount());
        assertEquals(1, component.retryCount());
        assertEquals(0, component.terminalCount());
        assertTrue(component.oldestUnresolvedAgeSeconds() >= 119L);
    }

    @Test
    void durableDebt_shouldClassifyEveryWalletOwnedWorkFromAuthoritativeTables() {
        String submissionId = UUID.randomUUID().toString();
        String cancellationId = UUID.randomUUID().toString();
        String tradeId = UUID.randomUUID().toString();
        try {
            insertInbox("ORDER_SUBMITTED", submissionId, "PENDING", 0, null);
            insertInbox("ORDER_CANCELLATION_RESULT", cancellationId,
                    "FAILED_PERMANENT", 1, "PERMANENT_INVARIANT");
            insertInbox("TRADE_EXECUTED", tradeId,
                    "FAILED_RETRYABLE", 2, "TRANSIENT_DATA_STORE");
            jdbc.update("""
                    INSERT INTO wallet_service.outbox
                        (event_type, routing_key, payload, status, attempt_count, created_at)
                    VALUES ('ProviderMatrixEvent', 'test.routing', '{}', 'FAILED', 3,
                            CURRENT_TIMESTAMP - INTERVAL '2 minutes')
                    """);

            ReflectionTestUtils.invokeMethod(durableDebt, "refresh");
            Map<String, DurableDebtSnapshot.ComponentDebt> components =
                    durableDebt.snapshot().components().stream().collect(Collectors.toMap(
                            DurableDebtSnapshot.ComponentDebt::work,
                            component -> component));

            assertDebt(components, "order_submission_inbox", 1, 0, 0);
            assertDebt(components, "cancellation_result_inbox", 1, 0, 1);
            assertDebt(components, "trade_execution_inbox", 1, 1, 0);
            assertDebt(components, "event_outbox", 1, 0, 1);
        } finally {
            jdbc.update("DELETE FROM wallet_service.outbox WHERE event_type = 'ProviderMatrixEvent'");
            jdbc.update("DELETE FROM wallet_service.message_inbox WHERE message_id IN (?, ?, ?)",
                    submissionId, cancellationId, tradeId);
            ReflectionTestUtils.invokeMethod(durableDebt, "refresh");
        }
    }

    private void insertInbox(
            String type, String id, String status, int attempts, String errorType) {
        jdbc.update("""
                INSERT INTO wallet_service.message_inbox
                    (message_type, message_id, payload, payload_hash, status, attempt_count,
                     error_type, received_at)
                VALUES (?, ?, '{}', 'hash', ?, ?, ?, CURRENT_TIMESTAMP - INTERVAL '2 minutes')
                """, type, id, status, attempts, errorType);
    }

    private static void assertDebt(
            Map<String, DurableDebtSnapshot.ComponentDebt> components,
            String work,
            long minimumTotal,
            long minimumRetry,
            long minimumTerminal) {
        var component = components.get(work);
        assertNotNull(component);
        assertTrue(component.totalCount() >= minimumTotal);
        assertTrue(component.retryCount() >= minimumRetry);
        assertTrue(component.terminalCount() >= minimumTerminal);
        assertTrue(component.oldestUnresolvedAgeSeconds() >= 119L);
    }

    private OrderSubmittedEvent event(int amount) {
        if (orderId == null) {
            orderId = UUID.randomUUID();
        }
        return OrderSubmittedEvent.builder()
                .orderId(orderId)
                .userId(UUID.nameUUIDFromBytes((orderId + ":user").getBytes()))
                .orderType("BUY")
                .price(100)
                .amount(amount)
                .build();
    }
}

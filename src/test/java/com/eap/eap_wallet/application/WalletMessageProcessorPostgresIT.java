package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderSubmittedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "eap.wallet.inbox-reconciler.initial-delay-ms=600000"
})
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
class WalletMessageProcessorPostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "eap.integration.postgres.url", "jdbc:postgresql://localhost:15433/eap_wallet_db"));
        registry.add("spring.datasource.username", () -> System.getProperty("eap.integration.postgres.user", "admin"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "eap.integration.postgres.password", "admin123"));
    }

    @Autowired WalletMessageInbox inbox;
    @Autowired WalletMessageProcessor processor;
    @Autowired WalletMessageReconciler reconciler;
    @Autowired JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID cancellationId = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM wallet_service.outbox WHERE payload LIKE ?", "%" + orderId + "%");
        jdbc.update("DELETE FROM wallet_service.order_asset_release_publications WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM wallet_service.order_cancellation_applications WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM wallet_service.message_inbox WHERE message_id IN (?, ?)",
                orderId.toString(), cancellationId.toString());
        jdbc.update("DELETE FROM wallet_service.order_submission_idempotency WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM wallet_service.wallets WHERE user_id = ?", userId);
    }

    @Test
    void reservationAndInboxApplied_shouldCommitAtomically() {
        seedWallet(10_000, 0);
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(orderId).userId(userId).orderType("BUY").price(100).amount(5).build();
        inbox.receiveOrderSubmitted(event);
        WalletMessageInbox.InboxEntry entry = claim("reservation-worker", orderId);

        processor.process(entry, "reservation-worker");

        assertEquals(9_500, value("available_currency"));
        assertEquals(500, value("locked_currency"));
        assertEquals("APPLIED", inboxStatus(orderId));
        assertEquals(1, outboxCount("OrderAssetReservationSucceededEvent"));
    }

    @Test
    void lostLease_shouldRollbackReservationAndOutboxTogether() {
        seedWallet(10_000, 0);
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(orderId).userId(userId).orderType("BUY").price(100).amount(5).build();
        inbox.receiveOrderSubmitted(event);
        WalletMessageInbox.InboxEntry entry = claim("real-owner", orderId);

        assertThrows(IllegalStateException.class, () -> processor.process(entry, "wrong-owner"));

        assertEquals(10_000, value("available_currency"));
        assertEquals(0, value("locked_currency"));
        assertEquals(0, outboxCount("OrderAssetReservationSucceededEvent"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_service.order_submission_idempotency WHERE order_id = ?
                """, Integer.class, orderId));
    }

    @Test
    void releasePublicationAndInboxApplied_shouldCommitExactlyOnce() {
        seedWallet(0, 500);
        OrderCancellationResultEvent event = OrderCancellationResultEvent.builder()
                .cancellationId(cancellationId).orderId(orderId).userId(userId)
                .outcome(OrderCancellationResultEvent.CANCELLED)
                .orderType("BUY").limitPrice(100).cancelledAmount(5).build();
        inbox.receiveCancellationResult(event);
        WalletMessageInbox.InboxEntry entry = claim("release-worker", cancellationId);

        processor.process(entry, "release-worker");
        assertEquals(WalletMessageInbox.ReceiveOutcome.DUPLICATE, inbox.receiveCancellationResult(event));

        assertEquals(500, value("available_currency"));
        assertEquals(0, value("locked_currency"));
        assertEquals("APPLIED", inboxStatus(cancellationId));
        assertEquals(1, outboxCount("OrderAssetReservationReleasedEvent"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_service.order_asset_release_publications WHERE order_id = ?
                """, Integer.class, orderId));
    }

    @Test
    void releaseExceedingLockedAsset_shouldBecomePermanentWithoutPartialEffect() {
        seedWallet(0, 200);
        OrderCancellationResultEvent event = OrderCancellationResultEvent.builder()
                .cancellationId(cancellationId).orderId(orderId).userId(userId)
                .outcome(OrderCancellationResultEvent.CANCELLED)
                .orderType("BUY").limitPrice(100).cancelledAmount(5).build();
        inbox.receiveCancellationResult(event);

        reconciler.reconcile();

        assertEquals(0, value("available_currency"));
        assertEquals(200, value("locked_currency"));
        assertEquals("FAILED_PERMANENT", inboxStatus(cancellationId));
        assertEquals("PERMANENT_ASSET_INVARIANT", inboxErrorType(cancellationId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_service.order_cancellation_applications WHERE order_id = ?
                """, Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_service.order_asset_release_publications WHERE order_id = ?
                """, Integer.class, orderId));
        assertEquals(0, outboxCount("OrderAssetReservationReleasedEvent"));
    }

    private void seedWallet(int availableCurrency, int lockedCurrency) {
        jdbc.update("""
                INSERT INTO wallet_service.wallets
                    (user_id, available_amount, locked_amount,
                     available_currency, locked_currency, version, update_time)
                VALUES (?, 0, 0, ?, ?, 0, CURRENT_TIMESTAMP)
                """, userId, availableCurrency, lockedCurrency);
    }

    private WalletMessageInbox.InboxEntry claim(String owner, UUID messageId) {
        List<WalletMessageInbox.InboxEntry> entries = inbox.claimRetryable(20, owner, 30_000);
        return entries.stream().filter(entry -> entry.messageId().equals(messageId.toString()))
                .findFirst().orElseThrow();
    }

    private int value(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM wallet_service.wallets WHERE user_id = ?",
                Integer.class, userId);
    }

    private String inboxStatus(UUID messageId) {
        return jdbc.queryForObject("SELECT status FROM wallet_service.message_inbox WHERE message_id = ?",
                String.class, messageId.toString());
    }

    private String inboxErrorType(UUID messageId) {
        return jdbc.queryForObject("SELECT error_type FROM wallet_service.message_inbox WHERE message_id = ?",
                String.class, messageId.toString());
    }

    private int outboxCount(String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_service.outbox
                WHERE event_type = ? AND payload LIKE ?
                """, Integer.class, type, "%" + orderId + "%");
    }
}

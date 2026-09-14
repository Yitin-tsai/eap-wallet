package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "eap.wallet.inbox-reconciler.initial-delay-ms=600000"
})
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
class WalletTradeExecutedInboxPostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "eap.integration.postgres.url", "jdbc:postgresql://localhost:15433/eap_wallet_db"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "eap.integration.postgres.user", "admin"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "eap.integration.postgres.password", "admin123"));
    }

    @Autowired WalletMessageInbox inbox;
    @Autowired WalletMessageProcessor processor;
    @Autowired WalletMessageReconciler reconciler;
    @Autowired JdbcTemplate jdbc;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID buyerOrderId = UUID.randomUUID();
    private final UUID sellerOrderId = UUID.randomUUID();
    private final String tradeId = "wallet-trade-inbox-" + UUID.randomUUID();

    @BeforeEach
    void seedWallets() {
        jdbc.update("""
                INSERT INTO wallet_service.wallets
                    (user_id, available_amount, locked_amount,
                     available_currency, locked_currency, version, update_time)
                VALUES (?, 0, 0, 0, 600, 0, CURRENT_TIMESTAMP),
                       (?, 0, 5, 0, 0, 0, CURRENT_TIMESTAMP)
                """, buyerId, sellerId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM wallet_service.message_inbox WHERE message_id = ?", tradeId);
        jdbc.update("DELETE FROM wallet_service.trade_settlements WHERE trade_id = ?", tradeId);
        jdbc.update("DELETE FROM wallet_service.wallets WHERE user_id IN (?, ?)", buyerId, sellerId);
    }

    @Test
    void tradeAndInboxApplied_shouldCommitAtomically() {
        TradeExecutedEvent event = event(5);
        assertEquals(WalletMessageInbox.ReceiveOutcome.ACCEPTED, inbox.receiveTradeExecuted(event));
        assertEquals(WalletMessageInbox.ReceiveOutcome.DUPLICATE, inbox.receiveTradeExecuted(event));
        WalletMessageInbox.InboxEntry entry = claim("trade-worker");

        WalletMessageProcessor.ProcessingOutcome outcome = processor.process(entry, "trade-worker");

        assertEquals(WalletMessageProcessor.ProcessingOutcome.TRADE_SETTLED, outcome);
        assertEquals("APPLIED", inboxStatus());
        assertEquals(1, settlementCount());
        assertWallet(buyerId, 5, 0, 50, 0);
        assertWallet(sellerId, 0, 0, 550, 0);
    }

    @Test
    void lostLease_shouldRollbackSettlementBalancesAndInboxCompletion() {
        inbox.receiveTradeExecuted(event(5));
        WalletMessageInbox.InboxEntry entry = claim("real-owner");

        assertThrows(IllegalStateException.class, () -> processor.process(entry, "wrong-owner"));

        assertEquals("IN_PROGRESS", inboxStatus());
        assertEquals(0, settlementCount());
        assertWallet(buyerId, 0, 0, 0, 600);
        assertWallet(sellerId, 0, 5, 0, 0);
    }

    @Test
    void expiredTradeLease_shouldBeReclaimedAndSettledExactlyOnce() {
        inbox.receiveTradeExecuted(event(5));
        WalletMessageInbox.InboxEntry staleEntry = claim("worker-a", 30_000);
        jdbc.update("""
                UPDATE wallet_service.message_inbox
                SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE message_type = 'TRADE_EXECUTED' AND message_id = ?
                """, tradeId);
        WalletMessageInbox.InboxEntry activeEntry = claim("worker-b", 30_000);

        assertThrows(IllegalStateException.class,
                () -> processor.process(staleEntry, "worker-a"));
        assertEquals(
                WalletMessageProcessor.ProcessingOutcome.TRADE_SETTLED,
                processor.process(activeEntry, "worker-b"));

        assertEquals("APPLIED", inboxStatus());
        assertEquals(1, settlementCount());
        assertWallet(buyerId, 5, 0, 50, 0);
        assertWallet(sellerId, 0, 0, 550, 0);
    }

    @Test
    void sameTradeIdWithDifferentPayload_shouldBecomeDurableIdentityConflict() {
        assertEquals(WalletMessageInbox.ReceiveOutcome.ACCEPTED, inbox.receiveTradeExecuted(event(5)));

        assertEquals(WalletMessageInbox.ReceiveOutcome.CONFLICT, inbox.receiveTradeExecuted(event(4)));

        assertEquals("FAILED_PERMANENT", inboxStatus());
        assertEquals("IDENTITY_CONFLICT", jdbc.queryForObject("""
                SELECT error_type FROM wallet_service.message_inbox
                WHERE message_type = 'TRADE_EXECUTED' AND message_id = ?
                """, String.class, tradeId));
        assertEquals(0, settlementCount());
    }

    @Test
    void conflictingPayloadAfterApplied_shouldRemainAppliedButExposeConflict() {
        TradeExecutedEvent event = event(5);
        long conflictsBefore = inbox.countIdentityConflicts();
        inbox.receiveTradeExecuted(event);
        processor.process(claim("trade-worker"), "trade-worker");

        assertEquals(WalletMessageInbox.ReceiveOutcome.CONFLICT, inbox.receiveTradeExecuted(event(4)));

        assertEquals("APPLIED", inboxStatus());
        assertEquals(conflictsBefore + 1, inbox.countIdentityConflicts());
        assertEquals(1, settlementCount());
        assertWallet(buyerId, 5, 0, 50, 0);
        assertWallet(sellerId, 0, 0, 550, 0);
    }

    @Test
    void legacySettlementWithoutPayloadIdentity_shouldNotBeSilentlyAcceptedAsDuplicate() {
        TradeExecutedEvent event = event(5);
        jdbc.update("""
                INSERT INTO wallet_service.trade_settlements
                    (trade_id, legacy_match_id, settled_at, event_payload_hash)
                VALUES (?, ?, ?, NULL)
                """, tradeId, event.getLegacyMatchId(), event.getOccurredAt());
        inbox.receiveTradeExecuted(event);
        WalletMessageInbox.InboxEntry entry = claim("trade-worker");

        assertThrows(WalletMessageIdentityConflictException.class,
                () -> processor.process(entry, "trade-worker"));

        assertEquals("IN_PROGRESS", inboxStatus());
        assertEquals(1, settlementCount());
        assertWallet(buyerId, 0, 0, 0, 600);
        assertWallet(sellerId, 0, 5, 0, 0);
    }

    @Test
    void existingSettlementWithSamePayloadHash_shouldApplyInboxAsDuplicateWithoutBalanceMutation() {
        TradeExecutedEvent event = event(5);
        inbox.receiveTradeExecuted(event);
        WalletMessageInbox.InboxEntry entry = claim("trade-worker");
        jdbc.update("""
                INSERT INTO wallet_service.trade_settlements
                    (trade_id, legacy_match_id, settled_at, event_payload_hash)
                VALUES (?, ?, ?, ?)
                """, tradeId, event.getLegacyMatchId(), event.getOccurredAt(), entry.payloadHash());

        assertEquals(
                WalletMessageProcessor.ProcessingOutcome.TRADE_DUPLICATE,
                processor.process(entry, "trade-worker"));

        assertEquals("APPLIED", inboxStatus());
        assertEquals(1, settlementCount());
        assertWallet(buyerId, 0, 0, 0, 600);
        assertWallet(sellerId, 0, 5, 0, 0);
    }

    @Test
    void existingSettlementWithDifferentPayloadHash_shouldBecomePermanentIdentityConflict() {
        TradeExecutedEvent event = event(5);
        inbox.receiveTradeExecuted(event);
        jdbc.update("""
                INSERT INTO wallet_service.trade_settlements
                    (trade_id, legacy_match_id, settled_at, event_payload_hash)
                VALUES (?, ?, ?, 'different-payload-hash')
                """, tradeId, event.getLegacyMatchId(), event.getOccurredAt());

        reconciler.reconcile();

        assertEquals("FAILED_PERMANENT", inboxStatus());
        assertEquals("PERMANENT_IDENTITY_CONFLICT", jdbc.queryForObject("""
                SELECT error_type
                FROM wallet_service.message_inbox
                WHERE message_type = 'TRADE_EXECUTED' AND message_id = ?
                """, String.class, tradeId));
        assertEquals(1, settlementCount());
        assertWallet(buyerId, 0, 0, 0, 600);
        assertWallet(sellerId, 0, 5, 0, 0);
    }

    private WalletMessageInbox.InboxEntry claim(String owner) {
        return claim(owner, 30_000);
    }

    private WalletMessageInbox.InboxEntry claim(String owner, long leaseMs) {
        List<WalletMessageInbox.InboxEntry> entries = inbox.claimRetryable(1_000, owner, leaseMs);
        return entries.stream()
                .filter(entry -> entry.messageType() == WalletMessageInbox.MessageType.TRADE_EXECUTED)
                .filter(entry -> entry.messageId().equals(tradeId))
                .findFirst()
                .orElseThrow();
    }

    private TradeExecutedEvent event(int quantity) {
        return TradeExecutedEvent.builder()
                .tradeId(tradeId)
                .legacyMatchId(Math.abs(tradeId.hashCode()))
                .marketId("WALLET_TRADE_INBOX_IT")
                .buyerId(buyerId)
                .sellerId(sellerId)
                .buyerOrderId(buyerOrderId)
                .sellerOrderId(sellerOrderId)
                .originBuyerPrice(120)
                .originSellerPrice(100)
                .dealPrice(110)
                .quantity(quantity)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    private String inboxStatus() {
        return jdbc.queryForObject("""
                SELECT status FROM wallet_service.message_inbox
                WHERE message_type = 'TRADE_EXECUTED' AND message_id = ?
                """, String.class, tradeId);
    }

    private int settlementCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_service.trade_settlements WHERE trade_id = ?",
                Integer.class, tradeId);
    }

    private void assertWallet(
            UUID userId,
            int availableAmount,
            int lockedAmount,
            int availableCurrency,
            int lockedCurrency) {
        jdbc.queryForObject("""
                SELECT available_amount, locked_amount, available_currency, locked_currency
                FROM wallet_service.wallets
                WHERE user_id = ?
                """, (rs, rowNum) -> {
            assertEquals(availableAmount, rs.getInt("available_amount"));
            assertEquals(lockedAmount, rs.getInt("locked_amount"));
            assertEquals(availableCurrency, rs.getInt("available_currency"));
            assertEquals(lockedCurrency, rs.getInt("locked_currency"));
            return null;
        }, userId);
    }
}

package com.eap.eap_wallet.application;

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
    @Autowired JdbcTemplate jdbc;

    private UUID orderId;

    @AfterEach
    void cleanup() {
        if (orderId != null) {
            jdbc.update("DELETE FROM wallet_service.message_inbox WHERE message_id = ?", orderId);
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
                """, Integer.class, orderId));
        assertEquals("FAILED_PERMANENT", jdbc.queryForObject("""
                SELECT status FROM wallet_service.message_inbox
                WHERE message_type = 'ORDER_SUBMITTED' AND message_id = ?
                """, String.class, orderId));
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
                """, orderId);

        List<WalletMessageInbox.InboxEntry> reclaimed = inbox.claimRetryable(1, "worker-b", 30_000);

        assertEquals(1, reclaimed.size());
        assertEquals(2, reclaimed.get(0).attemptCount());
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

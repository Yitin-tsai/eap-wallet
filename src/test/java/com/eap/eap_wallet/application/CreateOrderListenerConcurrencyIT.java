package com.eap.eap_wallet.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eap.common.event.OrderSubmittedEvent;
import com.eap.eap_wallet.configuration.repository.OrderSubmissionIdempotencyRepository;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "eap.wallet.inbox-reconciler.initial-delay-ms=600000"
})
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
class CreateOrderListenerConcurrencyIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty(
                "eap.integration.postgres.url", "jdbc:postgresql://localhost:15433/eap_wallet_db"));
        registry.add("spring.datasource.username", () -> System.getProperty(
                "eap.integration.postgres.user", "admin"));
        registry.add("spring.datasource.password", () -> System.getProperty(
                "eap.integration.postgres.password", "admin123"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.contexts", () -> "dev");
        registry.add("spring.liquibase.drop-first", () -> "false");
    }

    @Autowired
    private CreateOrderListener createOrderListener;

    @Autowired
    private WalletMessageInbox inbox;

    @Autowired
    private WalletMessageProcessor processor;

    @Autowired
    private WalletOrderReservationProcessor reservationProcessor;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OrderSubmissionIdempotencyRepository orderSubmissionIdempotencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentDuplicateOrderSubmittedEvent_shouldReserveFundsOnlyOnce() throws Exception {
        long outboxCountBefore = outboxRepository.count();
        long idempotencyCountBefore = orderSubmissionIdempotencyRepository.count();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        WalletEntity wallet = WalletEntity.builder()
                .userId(userId)
                .availableCurrency(100000)
                .lockedCurrency(0)
                .availableAmount(100)
                .lockedAmount(0)
                .build();
        walletRepository.saveAndFlush(wallet);

        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(orderId)
                .userId(userId)
                .price(1000)
                .amount(10)
                .orderType("BUY")
                .createdAt(LocalDateTime.now())
                .build();

        int concurrency = 24;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(callListenerAtSameTime(createOrderListener, event, ready, start)));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not become ready in time");
        start.countDown();

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        String owner = UUID.randomUUID().toString();
        List<WalletMessageInbox.InboxEntry> entries = inbox.claimRetryable(10, owner, 30_000);
        assertEquals(1, entries.size());
        processor.process(entries.get(0), owner);

        WalletEntity reloaded = walletRepository.findByUserId(userId);

        assertEquals(90000, reloaded.getAvailableCurrency());
        assertEquals(10000, reloaded.getLockedCurrency());
        assertEquals(outboxCountBefore + 1, outboxRepository.count());
        assertEquals(idempotencyCountBefore + 1, orderSubmissionIdempotencyRepository.count());
    }

    @Test
    void reservationShouldRecheckBalanceAfterWaitingForConcurrentWalletUpdate() throws Exception {
        UUID userId = UUID.randomUUID();
        WalletEntity wallet = WalletEntity.builder()
                .userId(userId)
                .availableCurrency(100)
                .lockedCurrency(0)
                .availableAmount(100)
                .lockedAmount(0)
                .build();
        walletRepository.saveAndFlush(wallet);

        OrderSubmittedEvent competingOrder = OrderSubmittedEvent.builder()
                .orderId(UUID.randomUUID())
                .userId(userId)
                .price(80)
                .amount(1)
                .orderType("BUY")
                .createdAt(LocalDateTime.now())
                .build();

        CountDownLatch walletLocked = new CountDownLatch(1);
        CountDownLatch allowConcurrentUpdate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> firstUpdate = executor.submit(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("""
                        SELECT user_id
                        FROM wallet_service.wallets
                        WHERE user_id = ?
                        FOR UPDATE
                        """, UUID.class, userId);
                walletLocked.countDown();
                await(allowConcurrentUpdate);
                jdbcTemplate.update("""
                        UPDATE wallet_service.wallets
                        SET available_currency = available_currency - 80,
                            locked_currency = locked_currency + 80,
                            version = version + 1
                        WHERE user_id = ?
                        """, userId);
            });
            return null;
        });

        assertTrue(walletLocked.await(5, TimeUnit.SECONDS), "first update did not lock the Wallet row");
        Future<WalletOrderReservationProcessor.ReservationOutcome> secondReservation =
                executor.submit(() -> reservationProcessor.reserve(competingOrder));

        waitForReservationLockWait();
        allowConcurrentUpdate.countDown();

        firstUpdate.get(10, TimeUnit.SECONDS);
        WalletOrderReservationProcessor.ReservationOutcome outcome =
                secondReservation.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        WalletEntity reloaded = walletRepository.findByUserId(userId);
        assertEquals(1, outcome.claimed());
        assertEquals(1, outcome.walletExists());
        assertEquals(0, outcome.reserved());
        assertEquals(20, reloaded.getAvailableCurrency());
        assertEquals(80, reloaded.getLockedCurrency());
    }

    @Test
    void databaseShouldRejectNegativeAggregateBalances() {
        UUID userId = UUID.randomUUID();
        walletRepository.saveAndFlush(WalletEntity.builder()
                .userId(userId)
                .availableCurrency(1)
                .lockedCurrency(1)
                .availableAmount(1)
                .lockedAmount(1)
                .build());

        assertThrows(DataIntegrityViolationException.class,
                () -> setBalance(userId, "available_currency", -1));
        assertThrows(DataIntegrityViolationException.class,
                () -> setBalance(userId, "locked_currency", -1));
        assertThrows(DataIntegrityViolationException.class,
                () -> setBalance(userId, "available_amount", -1));
        assertThrows(DataIntegrityViolationException.class,
                () -> setBalance(userId, "locked_amount", -1));
    }

    private Callable<Void> callListenerAtSameTime(
            CreateOrderListener listener,
            OrderSubmittedEvent event,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            listener.onOrderSubmitted(event);
            return null;
        };
    }

    private void waitForReservationLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%order_submission_idempotency%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("reservation did not wait on the Wallet row lock");
    }

    private void setBalance(UUID userId, String column, int value) {
        if (!List.of("available_currency", "locked_currency", "available_amount", "locked_amount")
                .contains(column)) {
            throw new IllegalArgumentException("Unexpected Wallet balance column: " + column);
        }
        jdbcTemplate.update("UPDATE wallet_service.wallets SET " + column + " = ? WHERE user_id = ?",
                value, userId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent Wallet update");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating Wallet concurrency test", e);
        }
    }
}

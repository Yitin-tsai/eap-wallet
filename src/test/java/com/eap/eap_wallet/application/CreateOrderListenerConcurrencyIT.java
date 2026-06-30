package com.eap.eap_wallet.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/eapdb",
    "spring.datasource.username=admin",
    "spring.datasource.password=admin123",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.liquibase.contexts=dev",
    "spring.liquibase.drop-first=false"
})
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
class CreateOrderListenerConcurrencyIT {

    @Autowired
    private CreateOrderListener createOrderListener;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OrderSubmissionIdempotencyRepository orderSubmissionIdempotencyRepository;

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

        WalletEntity reloaded = walletRepository.findByUserId(userId);

        assertEquals(90000, reloaded.getAvailableCurrency());
        assertEquals(10000, reloaded.getLockedCurrency());
        assertEquals(outboxCountBefore + 1, outboxRepository.count());
        assertEquals(idempotencyCountBefore + 1, orderSubmissionIdempotencyRepository.count());
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
}

package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
class WalletCancellationSettlementOrderingPostgresIT {

    private final UUID buyerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID buyerOrderId = UUID.randomUUID();
    private final UUID sellerOrderId = UUID.randomUUID();
    private final UUID cancellationId = UUID.randomUUID();
    private final String tradeId = "wallet-cancel-ordering-" + UUID.randomUUID();

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WalletOrderCancellationAppender cancellationAppender;
    private WalletTradeSettlementAppender settlementAppender;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty(
                        "eap.integration.postgres.url",
                        "jdbc:postgresql://localhost:15433/eap_wallet_db"),
                System.getProperty("eap.integration.postgres.user", "admin"),
                System.getProperty("eap.integration.postgres.password", "admin123"));
        jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        cancellationAppender = new WalletOrderCancellationAppender(namedJdbc);
        settlementAppender = new WalletTradeSettlementAppender(namedJdbc, mock(WalletMetrics.class));

        jdbc.update("""
                INSERT INTO wallet_service.wallets
                    (user_id, available_amount, locked_amount,
                     available_currency, locked_currency, version, update_time)
                VALUES (?, 0, 0, 0, 1000, 0, CURRENT_TIMESTAMP),
                       (?, 0, 4, 0, 0, 0, CURRENT_TIMESTAMP)
                """, buyerId, sellerId);
    }

    @AfterEach
    void tearDown() {
        if (jdbc == null) {
            return;
        }
        jdbc.update("DELETE FROM wallet_service.trade_settlements WHERE trade_id LIKE ?", tradeId + "%");
        jdbc.update("DELETE FROM wallet_service.order_cancellation_applications WHERE order_id = ?", buyerOrderId);
        jdbc.update("DELETE FROM wallet_service.wallets WHERE user_id IN (?, ?)", buyerId, sellerId);
    }

    @Test
    void cancellationBeforeTradeSettlement_shouldReleaseOnlyRemainderThenSettleEarlierTrade() {
        WalletOrderCancellationAppender.CancellationOutcome cancellation = cancel();

        assertTrue(cancellation.completed());
        assertApplication(cancellationId, buyerOrderId, 6);
        assertWallet(buyerId, 0, 0, 600, 400);

        WalletTradeSettlementAppender.SettlementOutcome settlement = settle();

        assertTrue(settlement.completed());
        assertFinalState();
    }

    @Test
    void tradeSettlementBeforeCancellation_shouldConvergeToSameBalances() {
        assertTrue(settle().completed());

        assertTrue(cancel().completed());

        assertFinalState();
    }

    @RepeatedTest(10)
    void concurrentCancellationAndSettlement_shouldUseOneLockOrderAndConverge() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<WalletOrderCancellationAppender.CancellationOutcome> cancellation = executor.submit(() -> {
                start.await();
                return cancel();
            });
            Future<WalletTradeSettlementAppender.SettlementOutcome> settlement = executor.submit(() -> {
                start.await();
                return settle();
            });

            start.countDown();

            assertTrue(cancellation.get(10, TimeUnit.SECONDS).completed());
            assertTrue(settlement.get(10, TimeUnit.SECONDS).completed());
            assertFinalState();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void duplicateCancellationResult_shouldNotReleaseAssetsTwice() {
        assertTrue(cancel().completed());

        WalletOrderCancellationAppender.CancellationOutcome duplicate = cancel();

        assertTrue(duplicate.duplicate());
        assertApplication(cancellationId, buyerOrderId, 6);
        assertWallet(buyerId, 0, 0, 600, 400);
    }

    @Test
    void reusedCancellationIdWithDifferentAmount_shouldFailIdentityCheck() {
        assertTrue(cancel().completed());

        assertThrows(WalletMessageIdentityConflictException.class, () -> transaction.execute(
                status -> cancellationAppender.release(cancellationEvent(5))));

        assertApplication(cancellationId, buyerOrderId, 6);
        assertWallet(buyerId, 0, 0, 600, 400);
    }

    @Test
    void differentCancellationIdForSameOrder_shouldNotReleaseAssetsTwice() {
        assertTrue(cancel().completed());
        OrderCancellationResultEvent conflict = cancellationEvent(6);
        conflict.setCancellationId(UUID.randomUUID());

        assertThrows(WalletMessageIdentityConflictException.class, () -> transaction.execute(
                status -> cancellationAppender.release(conflict)));

        assertApplication(cancellationId, buyerOrderId, 6);
        assertWallet(buyerId, 0, 0, 600, 400);
    }

    @Test
    void cancellationBeforeTwoEarlierPartialSettlements_shouldConverge() {
        assertTrue(cancel().completed());

        assertTrue(settle("-partial-1", 2).completed());
        assertTrue(settle("-partial-2", 2).completed());

        assertFinalState();
    }

    private WalletOrderCancellationAppender.CancellationOutcome cancel() {
        return transaction.execute(status -> cancellationAppender.release(cancellationEvent()));
    }

    private WalletTradeSettlementAppender.SettlementOutcome settle() {
        return settle("", 4);
    }

    private WalletTradeSettlementAppender.SettlementOutcome settle(String suffix, int quantity) {
        TradeExecutedEvent event = tradeEvent(suffix, quantity);
        return transaction.execute(status -> settlementAppender.append(event, event.getOccurredAt()));
    }

    private OrderCancellationResultEvent cancellationEvent() {
        return cancellationEvent(6);
    }

    private OrderCancellationResultEvent cancellationEvent(int cancelledAmount) {
        return OrderCancellationResultEvent.builder()
                .cancellationId(cancellationId)
                .orderId(buyerOrderId)
                .userId(buyerId)
                .outcome(OrderCancellationResultEvent.CANCELLED)
                .orderType("BUY")
                .limitPrice(100)
                .cancelledAmount(cancelledAmount)
                .decidedAt(LocalDateTime.now())
                .build();
    }

    private TradeExecutedEvent tradeEvent(String suffix, int quantity) {
        return TradeExecutedEvent.builder()
                .tradeId(tradeId + suffix)
                .legacyMatchId(Math.abs((tradeId + suffix).hashCode()))
                .buyerId(buyerId)
                .sellerId(sellerId)
                .buyerOrderId(buyerOrderId)
                .sellerOrderId(sellerOrderId)
                .originBuyerPrice(100)
                .originSellerPrice(90)
                .dealPrice(90)
                .quantity(quantity)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    private void assertFinalState() {
        assertApplication(cancellationId, buyerOrderId, 6);
        assertWallet(buyerId, 4, 0, 640, 0);
        assertWallet(sellerId, 0, 0, 360, 0);
    }

    private void assertApplication(UUID expectedCancellationId, UUID orderId, int cancelledQuantity) {
        jdbc.queryForObject("""
                SELECT cancellation_id, cancelled_quantity
                FROM wallet_service.order_cancellation_applications
                WHERE order_id = ?
                """, (rs, rowNum) -> {
            assertEquals(expectedCancellationId, rs.getObject("cancellation_id", UUID.class));
            assertEquals(cancelledQuantity, rs.getInt("cancelled_quantity"));
            return null;
        }, orderId);
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

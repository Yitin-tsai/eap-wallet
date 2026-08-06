package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
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
class WalletTradeSettlementAppenderPostgresIT {

    private JdbcTemplate jdbcTemplate;
    private WalletTradeSettlementAppender appender;
    private TransactionTemplate transactionTemplate;
    private List<UUID> userIds;
    private String tradePrefix;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty(
                        "eap.integration.postgres.url",
                        "jdbc:postgresql://localhost:15433/eap_wallet_db"),
                System.getProperty("eap.integration.postgres.user", "admin"),
                System.getProperty("eap.integration.postgres.password", "admin123"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        appender = new WalletTradeSettlementAppender(
                new NamedParameterJdbcTemplate(dataSource),
                mock(WalletMetrics.class));
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        userIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        tradePrefix = "wallet-lock-it-" + UUID.randomUUID();

        for (UUID userId : userIds) {
            jdbcTemplate.update("""
                    INSERT INTO wallet_service.wallets
                        (user_id, available_amount, locked_amount,
                         available_currency, locked_currency, version, update_time)
                    VALUES (?, 0, 100, 0, 10000, 0, CURRENT_TIMESTAMP)
                    """, userId);
        }
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM wallet_service.trade_settlements WHERE trade_id LIKE ?",
                tradePrefix + "%");
        if (userIds != null) {
            for (UUID userId : userIds) {
                jdbcTemplate.update("DELETE FROM wallet_service.wallets WHERE user_id = ?", userId);
            }
        }
    }

    @Test
    void concurrentCrossRoleSettlements_shouldCompleteWithoutDeadlock() throws Exception {
        TradeExecutedEvent firstTrade = event("-a1", userIds.get(0), userIds.get(1));
        TradeExecutedEvent secondTrade = event("-b1", userIds.get(1), userIds.get(0));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<WalletTradeSettlementAppender.SettlementOutcome> first =
                    executor.submit(() -> appendAtSameTime(firstTrade, ready, start));
            Future<WalletTradeSettlementAppender.SettlementOutcome> second =
                    executor.submit(() -> appendAtSameTime(secondTrade, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not become ready in time");
            start.countDown();

            assertTrue(first.get(10, TimeUnit.SECONDS).completed());
            assertTrue(second.get(10, TimeUnit.SECONDS).completed());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_service.trade_settlements WHERE trade_id LIKE ?",
                Long.class,
                tradePrefix + "%"));
        for (UUID userId : userIds) {
            jdbcTemplate.queryForObject("""
                    SELECT locked_amount, available_amount, locked_currency, available_currency
                    FROM wallet_service.wallets
                    WHERE user_id = ?
                    """, (resultSet, rowNum) -> {
                assertEquals(99, resultSet.getInt("locked_amount"));
                assertEquals(1, resultSet.getInt("available_amount"));
                assertEquals(9900, resultSet.getInt("locked_currency"));
                assertEquals(100, resultSet.getInt("available_currency"));
                return null;
            }, userId);
        }
    }

    @Test
    void missingSellerWallet_shouldNotCommitSettlementOrBuyerUpdate() {
        TradeExecutedEvent event = event("-missing-seller", userIds.get(0), UUID.randomUUID());

        assertThrows(IllegalStateException.class, () -> appendTransactionally(event));

        assertEquals(0, settlementCount(event.getTradeId()));
        assertWallet(userIds.get(0), 100, 0, 10000, 0);
    }

    @Test
    void insufficientSellerBalance_shouldRollbackSettlementAndBuyerUpdate() {
        jdbcTemplate.update(
                "UPDATE wallet_service.wallets SET locked_amount = 0 WHERE user_id = ?",
                userIds.get(1));
        TradeExecutedEvent event = event("-insufficient-seller", userIds.get(0), userIds.get(1));

        assertThrows(IllegalStateException.class, () -> appendTransactionally(event));

        assertEquals(0, settlementCount(event.getTradeId()));
        assertWallet(userIds.get(0), 100, 0, 10000, 0);
        assertWallet(userIds.get(1), 0, 0, 10000, 0);
    }

    private WalletTradeSettlementAppender.SettlementOutcome appendAtSameTime(
            TradeExecutedEvent event,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS), "start signal timed out");
        return appendTransactionally(event);
    }

    private WalletTradeSettlementAppender.SettlementOutcome appendTransactionally(
            TradeExecutedEvent event) {
        return transactionTemplate.execute(status -> appender.append(event, event.getOccurredAt()));
    }

    private long settlementCount(String tradeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_service.trade_settlements WHERE trade_id = ?",
                Long.class,
                tradeId);
    }

    private void assertWallet(
            UUID userId,
            int lockedAmount,
            int availableAmount,
            int lockedCurrency,
            int availableCurrency) {
        jdbcTemplate.queryForObject("""
                SELECT locked_amount, available_amount, locked_currency, available_currency
                FROM wallet_service.wallets
                WHERE user_id = ?
                """, (resultSet, rowNum) -> {
            assertEquals(lockedAmount, resultSet.getInt("locked_amount"));
            assertEquals(availableAmount, resultSet.getInt("available_amount"));
            assertEquals(lockedCurrency, resultSet.getInt("locked_currency"));
            assertEquals(availableCurrency, resultSet.getInt("available_currency"));
            return null;
        }, userId);
    }

    private TradeExecutedEvent event(String suffix, UUID buyerId, UUID sellerId) {
        return TradeExecutedEvent.builder()
                .tradeId(tradePrefix + suffix)
                .legacyMatchId(Math.abs(suffix.hashCode()))
                .buyerId(buyerId)
                .sellerId(sellerId)
                .buyerOrderId(UUID.randomUUID())
                .sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(100)
                .originSellerPrice(100)
                .dealPrice(100)
                .quantity(1)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}

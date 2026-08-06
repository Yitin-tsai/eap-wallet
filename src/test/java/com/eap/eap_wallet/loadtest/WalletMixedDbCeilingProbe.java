package com.eap.eap_wallet.loadtest;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WalletMixedDbCeilingProbe {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:15433/eap_wallet_db";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private static final String SEED_WALLET_SQL = """
            INSERT INTO wallet_service.wallets
                (user_id, available_amount, locked_amount, update_time,
                 available_currency, locked_currency, version)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?, 0)
            ON CONFLICT (user_id) DO UPDATE
            SET available_amount = EXCLUDED.available_amount,
                locked_amount = EXCLUDED.locked_amount,
                available_currency = EXCLUDED.available_currency,
                locked_currency = EXCLUDED.locked_currency,
                version = 0,
                update_time = CURRENT_TIMESTAMP
            """;

    private static final String RESERVATION_SQL = """
            WITH claimed AS (
                INSERT INTO wallet_service.order_submission_idempotency(order_id, user_id, recorded_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (order_id) DO NOTHING
                RETURNING order_id
            ),
            wallet_state AS (
                SELECT w.user_id,
                       CASE
                           WHEN ? = 'BUY' THEN w.available_currency >= ?
                           WHEN ? = 'SELL' THEN w.available_amount >= ?
                           ELSE false
                       END AS has_assets
                FROM wallet_service.wallets w
                JOIN claimed ON TRUE
                WHERE w.user_id = ?
            ),
            reserved AS (
                UPDATE wallet_service.wallets w
                SET available_currency = CASE WHEN ? = 'BUY'
                        THEN w.available_currency - ? ELSE w.available_currency END,
                    locked_currency = CASE WHEN ? = 'BUY'
                        THEN w.locked_currency + ? ELSE w.locked_currency END,
                    available_amount = CASE WHEN ? = 'SELL'
                        THEN w.available_amount - ? ELSE w.available_amount END,
                    locked_amount = CASE WHEN ? = 'SELL'
                        THEN w.locked_amount + ? ELSE w.locked_amount END,
                    version = version + 1,
                    update_time = CURRENT_TIMESTAMP
                FROM wallet_state ws
                WHERE w.user_id = ws.user_id
                  AND ws.has_assets
                RETURNING w.user_id
            ),
            outbox_inserted AS (
                INSERT INTO wallet_service.outbox
                    (event_type, routing_key, payload, status,
                     created_at, attempt_count, next_retry_at, updated_at)
                SELECT
                    CASE WHEN EXISTS (SELECT 1 FROM reserved)
                        THEN 'OrderConfirmedEvent' ELSE 'OrderFailedEvent' END,
                    CASE WHEN EXISTS (SELECT 1 FROM reserved)
                        THEN ? ELSE ? END,
                    CASE
                        WHEN false THEN ?
                        WHEN EXISTS (SELECT 1 FROM reserved) THEN ?
                        WHEN NOT EXISTS (SELECT 1 FROM wallet_state) THEN ?
                        ELSE ?
                    END,
                    'SENT',
                    CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM claimed
                RETURNING id
            )
            SELECT
                (SELECT COUNT(*) FROM claimed) AS claimed,
                (SELECT COUNT(*) FROM wallet_state) AS wallet_exists,
                (SELECT COUNT(*) FROM reserved) AS reserved,
                (SELECT COUNT(*) FROM outbox_inserted) AS outbox_inserted
            """;

    private static final String SETTLEMENT_SQL = """
            WITH settlement AS (
                INSERT INTO wallet_service.trade_settlements
                    (trade_id, legacy_match_id, settled_at)
                VALUES (?, ?, ?)
                ON CONFLICT (trade_id) DO NOTHING
                RETURNING trade_id
            ),
            buyer_update AS (
                UPDATE wallet_service.wallets
                SET locked_currency = locked_currency - ?,
                    available_currency = available_currency + ?,
                    available_amount = available_amount + ?,
                    version = version + 1,
                    update_time = CURRENT_TIMESTAMP
                WHERE user_id = ?
                  AND EXISTS (SELECT 1 FROM settlement)
                  AND locked_currency >= ?
                RETURNING 1
            ),
            seller_update AS (
                UPDATE wallet_service.wallets
                SET locked_amount = locked_amount - ?,
                    available_currency = available_currency + ?,
                    version = version + 1,
                    update_time = CURRENT_TIMESTAMP
                WHERE user_id = ?
                  AND EXISTS (SELECT 1 FROM settlement)
                  AND locked_amount >= ?
                RETURNING 1
            )
            SELECT
                (SELECT COUNT(*) FROM settlement) AS inserted_settlements,
                (SELECT COUNT(*) FROM buyer_update) AS updated_buyers,
                (SELECT COUNT(*) FROM seller_update) AS updated_sellers
            """;

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        seedWallets(config);
        Result result = run(config);
        printJson(config, result);
        if (result.reservationFailures() > 0 || result.settlementFailures() > 0) {
            throw new IllegalStateException("wallet mixed DB probe failed: reservationFailures="
                    + result.reservationFailures() + ", settlementFailures=" + result.settlementFailures());
        }
    }

    private static void seedWallets(Config config) throws SQLException {
        int tradesPerUser = (int) Math.ceil((double) config.trades() / config.usersPerSide());
        int lockedCurrency = (tradesPerUser + 100) * 100;
        int lockedAmount = tradesPerUser + 100;
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl(), config.username(), config.password());
             PreparedStatement statement = connection.prepareStatement(SEED_WALLET_SQL)) {
            connection.setAutoCommit(false);
            for (int index = 0; index < config.usersPerSide(); index++) {
                bindWalletSeed(statement, buyerId(config.marketId(), index),
                        config.seedBalance(), 0, config.seedBalance(), lockedCurrency);
                statement.addBatch();
                bindWalletSeed(statement, sellerId(config.marketId(), index),
                        config.seedBalance(), lockedAmount, config.seedBalance(), 0);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        }
    }

    private static void bindWalletSeed(
            PreparedStatement statement,
            UUID userId,
            int availableAmount,
            int lockedAmount,
            int availableCurrency,
            int lockedCurrency) throws SQLException {
        statement.setObject(1, userId);
        statement.setInt(2, availableAmount);
        statement.setInt(3, lockedAmount);
        statement.setInt(4, availableCurrency);
        statement.setInt(5, lockedCurrency);
    }

    private static Result run(Config config) throws InterruptedException {
        int totalWorkers = config.reservationWorkers() + config.settlementWorkers();
        ExecutorService executor = Executors.newFixedThreadPool(totalWorkers);
        CountDownLatch ready = new CountDownLatch(totalWorkers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(Math.multiplyExact(config.trades(), 2));
        AtomicInteger reservationNext = new AtomicInteger();
        AtomicInteger settlementNext = new AtomicInteger();
        AtomicInteger reservationRemaining = new AtomicInteger(config.trades());
        AtomicInteger settlementRemaining = new AtomicInteger(config.trades());
        AtomicInteger reservationFailures = new AtomicInteger();
        AtomicInteger settlementFailures = new AtomicInteger();
        AtomicLong reservationCompletedAt = new AtomicLong();
        AtomicLong settlementCompletedAt = new AtomicLong();
        List<Long> reservationLatencies = Collections.synchronizedList(new ArrayList<>(config.trades()));
        List<Long> settlementLatencies = Collections.synchronizedList(new ArrayList<>(config.trades()));

        for (int worker = 0; worker < config.reservationWorkers(); worker++) {
            executor.execute(() -> runReservationWorker(
                    config, ready, start, done, reservationNext, reservationRemaining,
                    reservationFailures, reservationCompletedAt, reservationLatencies));
        }
        for (int worker = 0; worker < config.settlementWorkers(); worker++) {
            executor.execute(() -> runSettlementWorker(
                    config, ready, start, done, settlementNext, settlementRemaining,
                    settlementFailures, settlementCompletedAt, settlementLatencies));
        }

        if (!ready.await(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            throw new IllegalStateException("wallet mixed DB probe workers did not become ready");
        }
        long startedAt = System.nanoTime();
        start.countDown();
        done.await();
        long completedAt = System.nanoTime();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        List<Long> sortedReservationLatencies = new ArrayList<>(reservationLatencies);
        List<Long> sortedSettlementLatencies = new ArrayList<>(settlementLatencies);
        Collections.sort(sortedReservationLatencies);
        Collections.sort(sortedSettlementLatencies);
        return new Result(
                reservationFailures.get(),
                settlementFailures.get(),
                secondsBetween(startedAt, reservationCompletedAt.get()),
                secondsBetween(startedAt, settlementCompletedAt.get()),
                secondsBetween(startedAt, completedAt),
                percentileMillis(sortedReservationLatencies, 0.50),
                percentileMillis(sortedReservationLatencies, 0.95),
                percentileMillis(sortedReservationLatencies, 0.99),
                percentileMillis(sortedSettlementLatencies, 0.50),
                percentileMillis(sortedSettlementLatencies, 0.95),
                percentileMillis(sortedSettlementLatencies, 0.99));
    }

    private static void runReservationWorker(
            Config config,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicInteger next,
            AtomicInteger remaining,
            AtomicInteger failures,
            AtomicLong completedAt,
            List<Long> latencies) {
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl(), config.username(), config.password());
             PreparedStatement statement = connection.prepareStatement(RESERVATION_SQL)) {
            connection.setAutoCommit(false);
            ready.countDown();
            start.await();
            while (true) {
                int index = next.getAndIncrement();
                if (index >= config.trades()) {
                    return;
                }
                long startedAt = System.nanoTime();
                try {
                    bindReservation(statement, config, index + 1L);
                    ReservationCounts counts = executeReservation(statement);
                    if (!counts.completed()) {
                        throw new IllegalStateException("unexpected reservation counts=" + counts);
                    }
                    connection.commit();
                    latencies.add(System.nanoTime() - startedAt);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    rollbackQuietly(connection);
                    logFirstFailures("reservation", index, e, failures.get());
                } finally {
                    markCompleted(remaining, completedAt);
                    done.countDown();
                }
            }
        } catch (Exception e) {
            ready.countDown();
            failRemaining("reservation-worker", config.trades(), next, remaining, failures, completedAt, done, e);
        }
    }

    private static void runSettlementWorker(
            Config config,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicInteger next,
            AtomicInteger remaining,
            AtomicInteger failures,
            AtomicLong completedAt,
            List<Long> latencies) {
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl(), config.username(), config.password());
             PreparedStatement statement = connection.prepareStatement(SETTLEMENT_SQL)) {
            connection.setAutoCommit(false);
            ready.countDown();
            start.await();
            while (true) {
                int index = next.getAndIncrement();
                if (index >= config.trades()) {
                    return;
                }
                long startedAt = System.nanoTime();
                try {
                    bindSettlement(statement, config, index + 1L);
                    SettlementCounts counts = executeSettlement(statement);
                    if (!counts.completed()) {
                        throw new IllegalStateException("unexpected settlement counts=" + counts);
                    }
                    connection.commit();
                    latencies.add(System.nanoTime() - startedAt);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    rollbackQuietly(connection);
                    logFirstFailures("settlement", index, e, failures.get());
                } finally {
                    markCompleted(remaining, completedAt);
                    done.countDown();
                }
            }
        } catch (Exception e) {
            ready.countDown();
            failRemaining("settlement-worker", config.trades(), next, remaining, failures, completedAt, done, e);
        }
    }

    private static ReservationCounts executeReservation(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return new ReservationCounts(0, 0, 0, 0);
            }
            return new ReservationCounts(
                    rs.getInt("claimed"),
                    rs.getInt("wallet_exists"),
                    rs.getInt("reserved"),
                    rs.getInt("outbox_inserted"));
        }
    }

    private static SettlementCounts executeSettlement(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return new SettlementCounts(0, 0, 0);
            }
            return new SettlementCounts(
                    rs.getInt("inserted_settlements"),
                    rs.getInt("updated_buyers"),
                    rs.getInt("updated_sellers"));
        }
    }

    private static void bindReservation(PreparedStatement statement, Config config, long sequence)
            throws SQLException {
        UUID orderId = orderId(config.marketId(), sequence);
        UUID userId = buyerId(config.marketId(), userIndex(config, sequence));
        String confirmedPayload = "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId
                + "\",\"marketId\":\"" + config.marketId() + "\",\"marketSequence\":" + sequence
                + ",\"orderType\":\"BUY\",\"price\":100,\"amount\":1}";
        String failedPayload = "{\"orderId\":\"" + orderId + "\",\"reason\":\"INSUFFICIENT_ASSETS\"}";
        String walletMissingPayload = "{\"orderId\":\"" + orderId + "\",\"reason\":\"WALLET_NOT_FOUND\"}";

        statement.setObject(1, orderId);
        statement.setObject(2, userId);
        statement.setString(3, "BUY");
        statement.setInt(4, 100);
        statement.setString(5, "BUY");
        statement.setInt(6, 1);
        statement.setObject(7, userId);
        statement.setString(8, "BUY");
        statement.setInt(9, 100);
        statement.setString(10, "BUY");
        statement.setInt(11, 100);
        statement.setString(12, "BUY");
        statement.setInt(13, 1);
        statement.setString(14, "BUY");
        statement.setInt(15, 1);
        statement.setString(16, "order.confirmed");
        statement.setString(17, "order.failed");
        statement.setString(18, failedPayload);
        statement.setString(19, confirmedPayload);
        statement.setString(20, walletMissingPayload);
        statement.setString(21, failedPayload);
    }

    private static void bindSettlement(PreparedStatement statement, Config config, long sequence)
            throws SQLException {
        int userIndex = userIndex(config, sequence);
        statement.setString(1, config.marketId() + "-trade-" + sequence);
        statement.setLong(2, sequence);
        statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
        statement.setInt(4, 100);
        statement.setInt(5, 0);
        statement.setInt(6, 1);
        statement.setObject(7, buyerId(config.marketId(), userIndex));
        statement.setInt(8, 100);
        statement.setInt(9, 1);
        statement.setInt(10, 100);
        statement.setObject(11, sellerId(config.marketId(), userIndex));
        statement.setInt(12, 1);
    }

    private static int userIndex(Config config, long sequence) {
        return (int) ((sequence - 1) % config.usersPerSide());
    }

    private static UUID orderId(String marketId, long sequence) {
        return UUID.nameUUIDFromBytes((marketId + ":order:" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID buyerId(String marketId, int index) {
        return UUID.nameUUIDFromBytes((marketId + ":buyer:" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID sellerId(String marketId, int index) {
        return UUID.nameUUIDFromBytes((marketId + ":seller:" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static void markCompleted(AtomicInteger remaining, AtomicLong completedAt) {
        if (remaining.decrementAndGet() == 0) {
            completedAt.compareAndSet(0, System.nanoTime());
        }
    }

    private static void failRemaining(
            String label,
            int events,
            AtomicInteger next,
            AtomicInteger remaining,
            AtomicInteger failures,
            AtomicLong completedAt,
            CountDownLatch done,
            Exception cause) {
        System.err.printf("wallet mixed DB %s failed: %s%n", label, cause.getMessage());
        int index;
        while ((index = next.getAndIncrement()) < events) {
            failures.incrementAndGet();
            markCompleted(remaining, completedAt);
            done.countDown();
        }
    }

    private static void logFirstFailures(String label, int index, Exception error, int failures) {
        if (failures <= 10) {
            System.err.printf("wallet mixed DB %s failed: index=%d, error=%s%n", label, index, error.getMessage());
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }

    private static double secondsBetween(long startedAt, long completedAt) {
        return Math.max(completedAt - startedAt, 0) / 1_000_000_000.0;
    }

    private static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000.0;
    }

    private static void printJson(Config config, Result result) {
        System.out.println("{");
        System.out.println("  \"mode\": \"walletMixedDbCeilingProbe\",");
        System.out.printf("  \"marketId\": \"%s\",%n", config.marketId());
        System.out.printf("  \"trades\": %d,%n", config.trades());
        System.out.printf("  \"usersPerSide\": %d,%n", config.usersPerSide());
        System.out.printf("  \"reservationWorkers\": %d,%n", config.reservationWorkers());
        System.out.printf("  \"settlementWorkers\": %d,%n", config.settlementWorkers());
        System.out.printf("  \"reservationFailures\": %d,%n", result.reservationFailures());
        System.out.printf("  \"settlementFailures\": %d,%n", result.settlementFailures());
        System.out.printf("  \"reservationSeconds\": %.3f,%n", result.reservationSeconds());
        System.out.printf("  \"settlementSeconds\": %.3f,%n", result.settlementSeconds());
        System.out.printf("  \"mixedSeconds\": %.3f,%n", result.mixedSeconds());
        System.out.printf("  \"reservationTps\": %.2f,%n",
                config.trades() / Math.max(result.reservationSeconds(), 0.001));
        System.out.printf("  \"settlementTps\": %.2f,%n",
                config.trades() / Math.max(result.settlementSeconds(), 0.001));
        System.out.printf("  \"mixedTradeCyclesTps\": %.2f,%n",
                config.trades() / Math.max(result.mixedSeconds(), 0.001));
        System.out.printf("  \"combinedDbOperationsTps\": %.2f,%n",
                (config.trades() * 2.0) / Math.max(result.mixedSeconds(), 0.001));
        System.out.printf("  \"reservationP50Ms\": %.3f,%n", result.reservationP50Ms());
        System.out.printf("  \"reservationP95Ms\": %.3f,%n", result.reservationP95Ms());
        System.out.printf("  \"reservationP99Ms\": %.3f,%n", result.reservationP99Ms());
        System.out.printf("  \"settlementP50Ms\": %.3f,%n", result.settlementP50Ms());
        System.out.printf("  \"settlementP95Ms\": %.3f,%n", result.settlementP95Ms());
        System.out.printf("  \"settlementP99Ms\": %.3f%n", result.settlementP99Ms());
        System.out.println("}");
    }

    private record ReservationCounts(int claimed, int walletExists, int reserved, int outboxInserted) {
        boolean completed() {
            return claimed == 1 && walletExists == 1 && reserved == 1 && outboxInserted == 1;
        }
    }

    private record SettlementCounts(int insertedSettlements, int updatedBuyers, int updatedSellers) {
        boolean completed() {
            return insertedSettlements == 1 && updatedBuyers == 1 && updatedSellers == 1;
        }
    }

    private record Result(
            int reservationFailures,
            int settlementFailures,
            double reservationSeconds,
            double settlementSeconds,
            double mixedSeconds,
            double reservationP50Ms,
            double reservationP95Ms,
            double reservationP99Ms,
            double settlementP50Ms,
            double settlementP95Ms,
            double settlementP99Ms) {
    }

    private record Config(
            String jdbcUrl,
            String username,
            String password,
            String marketId,
            int trades,
            int usersPerSide,
            int reservationWorkers,
            int settlementWorkers,
            int seedBalance) {

        private static Config from(String[] args) {
            Config config = new Config(
                    stringArg(args, "--jdbc-url", DEFAULT_JDBC_URL),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--market-id", "WALLET_MIXED_DB_" + UUID.randomUUID()),
                    intArg(args, "--trades", 10_000),
                    intArg(args, "--users-per-side", 500),
                    intArg(args, "--reservation-workers", 28),
                    intArg(args, "--settlement-workers", 12),
                    intArg(args, "--seed-balance", 10_000_000));
            if (config.trades() <= 0 || config.usersPerSide() <= 0
                    || config.reservationWorkers() <= 0 || config.settlementWorkers() <= 0) {
                throw new IllegalArgumentException("trades, users and worker counts must be positive");
            }
            return config;
        }

        private static int intArg(String[] args, String name, int defaultValue) {
            return Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static String stringArg(String[] args, String name, String defaultValue) {
            for (int i = 0; i < args.length - 1; i++) {
                if (name.equals(args[i])) {
                    return args[i + 1];
                }
            }
            return defaultValue;
        }
    }
}

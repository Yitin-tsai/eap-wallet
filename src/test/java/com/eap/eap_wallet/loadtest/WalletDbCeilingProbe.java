package com.eap.eap_wallet.loadtest;

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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WalletDbCeilingProbe {

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
                update_time = CURRENT_TIMESTAMP
            """;

    private static final String SETTLEMENT_SQL = """
            WITH settlement AS (
                INSERT INTO wallet_service.trade_settlements
                    (trade_id, legacy_match_id, settled_at,
                     buyer_id, seller_id, buyer_order_id, seller_order_id,
                     deal_price, quantity, buyer_locked_currency, buyer_refund_currency,
                     seller_received_currency, event_status, attempt_count,
                     next_retry_at, updated_at)
                VALUES
                    (?, ?, ?,
                     ?, ?, ?, ?,
                     ?, ?, ?, ?,
                     ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
        if (config.seedWallets()) {
            seedWallets(config);
        }
        Result result = run(config);
        printJson(config, result);
        if (result.failures() > 0) {
            throw new IllegalStateException("DB ceiling probe failed rows=" + result.failures());
        }
    }

    private static void seedWallets(Config config) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password());
             PreparedStatement statement = connection.prepareStatement(SEED_WALLET_SQL)) {
            connection.setAutoCommit(false);
            int batched = 0;
            for (int index = 1; index <= config.events(); index++) {
                bindWalletSeed(statement, buyerId(index), config.seedBalance());
                statement.addBatch();
                bindWalletSeed(statement, sellerId(index), config.seedBalance());
                statement.addBatch();
                batched += 2;
                if (batched >= 1000) {
                    statement.executeBatch();
                    connection.commit();
                    batched = 0;
                }
            }
            if (batched > 0) {
                statement.executeBatch();
                connection.commit();
            }
        }
    }

    private static void bindWalletSeed(PreparedStatement statement, UUID userId, int balance) throws SQLException {
        statement.setObject(1, userId);
        statement.setInt(2, balance);
        statement.setInt(3, balance);
        statement.setInt(4, balance);
        statement.setInt(5, balance);
    }

    private static Result run(Config config) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(config.workers());
        CountDownLatch done = new CountDownLatch(config.events());
        Semaphore inFlight = new Semaphore(config.workers() * Math.max(config.batchSize(), 1));
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(config.events()));

        long started = System.nanoTime();
        for (int worker = 0; worker < config.workers(); worker++) {
            executor.execute(() -> runWorker(config, next, done, inFlight, failures, latenciesNanos));
        }
        done.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;

        List<Long> sorted = new ArrayList<>(latenciesNanos);
        Collections.sort(sorted);
        return new Result(
                config.events() - failures.get(),
                failures.get(),
                elapsedSeconds,
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99));
    }

    private static void runWorker(
            Config config,
            AtomicInteger next,
            CountDownLatch done,
            Semaphore inFlight,
            AtomicInteger failures,
            List<Long> latenciesNanos) {
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password());
             PreparedStatement statement = connection.prepareStatement(SETTLEMENT_SQL)) {
            connection.setAutoCommit(config.mode() == Mode.AUTOCOMMIT);
            int uncommitted = 0;
            while (true) {
                int index = next.getAndIncrement();
                if (index >= config.events()) {
                    commitIfNeeded(connection, config, uncommitted);
                    return;
                }
                inFlight.acquire();
                long rowStarted = System.nanoTime();
                try {
                    bindSettlement(statement, config.marketId(), index + 1L);
                    SettlementCounts counts = executeSettlement(statement);
                    if (!counts.completed()) {
                        throw new IllegalStateException("unexpected settlement counts=" + counts);
                    }
                    uncommitted++;
                    if (config.mode() == Mode.TRANSACTION_PER_ROW
                            || (config.mode() == Mode.GROUPED_TRANSACTION && uncommitted >= config.batchSize())) {
                        connection.commit();
                        uncommitted = 0;
                    }
                    latenciesNanos.add(System.nanoTime() - rowStarted);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    rollbackQuietly(connection, config);
                    uncommitted = 0;
                    if (failures.get() <= 10) {
                        System.err.printf("wallet probe row failed: index=%d, error=%s%n", index, e.getMessage());
                    }
                } finally {
                    inFlight.release();
                    done.countDown();
                }
            }
        } catch (Exception e) {
            int remaining;
            do {
                remaining = next.getAndIncrement();
                if (remaining < config.events()) {
                    failures.incrementAndGet();
                    done.countDown();
                }
            } while (remaining < config.events());
            System.err.printf("wallet probe worker failed: %s%n", e.getMessage());
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

    private static void bindSettlement(PreparedStatement statement, String marketId, long sequence) throws SQLException {
        String tradeId = marketId + "-" + sequence;
        int dealPrice = 100;
        int quantity = 1;
        int originalLockedCurrency = 100;
        int refundCurrency = originalLockedCurrency - (dealPrice * quantity);
        int dealCurrency = dealPrice * quantity;

        statement.setString(1, tradeId);
        statement.setLong(2, sequence);
        statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
        statement.setObject(4, buyerId(sequence));
        statement.setObject(5, sellerId(sequence));
        statement.setObject(6, uuid(sequence, 3));
        statement.setObject(7, uuid(sequence, 4));
        statement.setInt(8, dealPrice);
        statement.setInt(9, quantity);
        statement.setInt(10, originalLockedCurrency);
        statement.setInt(11, refundCurrency);
        statement.setInt(12, dealCurrency);
        statement.setInt(13, originalLockedCurrency);
        statement.setInt(14, refundCurrency);
        statement.setInt(15, quantity);
        statement.setObject(16, buyerId(sequence));
        statement.setInt(17, originalLockedCurrency);
        statement.setInt(18, quantity);
        statement.setInt(19, dealCurrency);
        statement.setObject(20, sellerId(sequence));
        statement.setInt(21, quantity);
    }

    private static UUID buyerId(long sequence) {
        return uuid(sequence, 1);
    }

    private static UUID sellerId(long sequence) {
        return uuid(sequence, 2);
    }

    private static UUID uuid(long sequence, long salt) {
        return new UUID(sequence, salt);
    }

    private static void commitIfNeeded(Connection connection, Config config, int uncommitted) throws SQLException {
        if (config.mode() != Mode.AUTOCOMMIT && uncommitted > 0) {
            connection.commit();
        }
    }

    private static void rollbackQuietly(Connection connection, Config config) {
        if (config.mode() == Mode.AUTOCOMMIT) {
            return;
        }
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
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
        System.out.printf("  \"mode\": \"walletDbCeilingProbe\",%n");
        System.out.printf("  \"transactionMode\": \"%s\",%n", config.mode().name().toLowerCase());
        System.out.printf("  \"marketId\": \"%s\",%n", config.marketId());
        System.out.printf("  \"events\": %d,%n", config.events());
        System.out.printf("  \"workers\": %d,%n", config.workers());
        System.out.printf("  \"batchSize\": %d,%n", config.batchSize());
        System.out.printf("  \"seedWallets\": %s,%n", config.seedWallets());
        System.out.printf("  \"completed\": %d,%n", result.completed());
        System.out.printf("  \"failures\": %d,%n", result.failures());
        System.out.printf("  \"elapsedSeconds\": %.3f,%n", result.elapsedSeconds());
        System.out.printf("  \"settlementTps\": %.2f,%n", result.completed() / Math.max(result.elapsedSeconds(), 0.001));
        System.out.printf("  \"p50Ms\": %.3f,%n", result.p50Ms());
        System.out.printf("  \"p95Ms\": %.3f,%n", result.p95Ms());
        System.out.printf("  \"p99Ms\": %.3f%n", result.p99Ms());
        System.out.println("}");
    }

    private enum Mode {
        AUTOCOMMIT,
        TRANSACTION_PER_ROW,
        GROUPED_TRANSACTION
    }

    private record SettlementCounts(int insertedSettlements, int updatedBuyers, int updatedSellers) {
        boolean completed() {
            return insertedSettlements == 1 && updatedBuyers == 1 && updatedSellers == 1;
        }
    }

    private record Result(
            int completed,
            int failures,
            double elapsedSeconds,
            double p50Ms,
            double p95Ms,
            double p99Ms) {
    }

    private record Config(
            String jdbcUrl,
            String username,
            String password,
            String marketId,
            int events,
            int workers,
            int batchSize,
            int seedBalance,
            boolean seedWallets,
            Mode mode) {

        private static Config from(String[] args) {
            Mode mode = Mode.valueOf(stringArg(args, "--mode", "transaction_per_row").toUpperCase());
            return new Config(
                    stringArg(args, "--jdbc-url", DEFAULT_JDBC_URL),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--market-id", "WALLET_DB_CEILING_" + UUID.randomUUID()),
                    intArg(args, "--events", 10_000),
                    intArg(args, "--workers", 16),
                    intArg(args, "--batch-size", 100),
                    intArg(args, "--seed-balance", 1_000_000),
                    booleanArg(args, "--seed-wallets", true),
                    mode);
        }

        private static int intArg(String[] args, String name, int defaultValue) {
            return Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static boolean booleanArg(String[] args, String name, boolean defaultValue) {
            return Boolean.parseBoolean(stringArg(args, name, String.valueOf(defaultValue)));
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

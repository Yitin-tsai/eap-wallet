package com.eap.eap_wallet.loadtest;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

public class WalletOrderSubmissionDbCeilingProbe {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:15433/eap_wallet_db";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private static final String ORDER_CONFIRMED_KEY = "order.confirmed";
    private static final String ORDER_FAILED_KEY = "order.failed";

    private static final String SEED_WALLET_SQL = """
            INSERT INTO wallet_service.wallets
                (user_id, available_amount, locked_amount, update_time,
                 available_currency, locked_currency, version)
            VALUES (?, ?, 0, CURRENT_TIMESTAMP, ?, 0, 0)
            ON CONFLICT (user_id) DO UPDATE
            SET available_amount = EXCLUDED.available_amount,
                locked_amount = 0,
                available_currency = EXCLUDED.available_currency,
                locked_currency = 0,
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
                    'PENDING',
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

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        if (config.seedWallets()) {
            seedWallets(config);
        }
        Result result = run(config);
        printJson(config, result);
        if (result.failures() > 0) {
            throw new IllegalStateException("wallet order-submission DB ceiling probe failed rows=" + result.failures());
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
                bindWalletSeed(statement, userId(config.marketId(), index), config.seedBalance());
                statement.addBatch();
                batched++;
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
             PreparedStatement statement = connection.prepareStatement(RESERVATION_SQL)) {
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
                    bindReservation(statement, config, index + 1L);
                    ReservationCounts counts = executeReservation(statement);
                    if (!counts.completed()) {
                        throw new IllegalStateException("unexpected reservation counts=" + counts);
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
                        System.err.printf("wallet order-submission probe row failed: index=%d, error=%s%n",
                                index, e.getMessage());
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
            System.err.printf("wallet order-submission probe worker failed: %s%n", e.getMessage());
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

    private static void bindReservation(PreparedStatement statement, Config config, long sequence) throws SQLException {
        String side = config.side().sideFor(sequence);
        int price = 100;
        int amount = 1;
        int requiredCurrency = price * amount;
        UUID orderId = orderId(config.marketId(), sequence);
        UUID userId = userId(config.marketId(), sequence);
        String confirmedPayload = "{\"orderId\":\"" + orderId + "\",\"marketId\":\"" + config.marketId()
                + "\",\"orderType\":\"" + side + "\",\"price\":" + price + ",\"amount\":" + amount + "}";
        String failedPayload = "{\"orderId\":\"" + orderId + "\",\"reason\":\"INSUFFICIENT_ASSETS\"}";
        String walletMissingPayload = "{\"orderId\":\"" + orderId + "\",\"reason\":\"WALLET_NOT_FOUND\"}";

        statement.setObject(1, orderId);
        statement.setObject(2, userId);
        statement.setString(3, side);
        statement.setInt(4, requiredCurrency);
        statement.setString(5, side);
        statement.setInt(6, amount);
        statement.setObject(7, userId);
        statement.setString(8, side);
        statement.setInt(9, requiredCurrency);
        statement.setString(10, side);
        statement.setInt(11, requiredCurrency);
        statement.setString(12, side);
        statement.setInt(13, amount);
        statement.setString(14, side);
        statement.setInt(15, amount);
        statement.setString(16, ORDER_CONFIRMED_KEY);
        statement.setString(17, ORDER_FAILED_KEY);
        statement.setString(18, failedPayload);
        statement.setString(19, confirmedPayload);
        statement.setString(20, walletMissingPayload);
        statement.setString(21, failedPayload);
    }

    private static UUID orderId(String marketId, long sequence) {
        return UUID.nameUUIDFromBytes((marketId + ":order:" + sequence).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID userId(String marketId, long sequence) {
        return UUID.nameUUIDFromBytes((marketId + ":user:" + sequence).getBytes(StandardCharsets.UTF_8));
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
        System.out.printf("  \"mode\": \"walletOrderSubmissionDbCeilingProbe\",%n");
        System.out.printf("  \"transactionMode\": \"%s\",%n", config.mode().name().toLowerCase());
        System.out.printf("  \"marketId\": \"%s\",%n", config.marketId());
        System.out.printf("  \"events\": %d,%n", config.events());
        System.out.printf("  \"workers\": %d,%n", config.workers());
        System.out.printf("  \"batchSize\": %d,%n", config.batchSize());
        System.out.printf("  \"side\": \"%s\",%n", config.side().name().toLowerCase());
        System.out.printf("  \"seedWallets\": %s,%n", config.seedWallets());
        System.out.printf("  \"completed\": %d,%n", result.completed());
        System.out.printf("  \"failures\": %d,%n", result.failures());
        System.out.printf("  \"elapsedSeconds\": %.3f,%n", result.elapsedSeconds());
        System.out.printf("  \"reservationTps\": %.2f,%n", result.completed() / Math.max(result.elapsedSeconds(), 0.001));
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

    private enum Side {
        BUY,
        SELL,
        MIXED;

        String sideFor(long sequence) {
            if (this == MIXED) {
                return sequence % 2 == 0 ? "SELL" : "BUY";
            }
            return name();
        }
    }

    private record ReservationCounts(int claimed, int walletExists, int reserved, int outboxInserted) {
        boolean completed() {
            return claimed == 1 && walletExists == 1 && reserved == 1 && outboxInserted == 1;
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
            Mode mode,
            Side side) {

        private static Config from(String[] args) {
            Mode mode = Mode.valueOf(stringArg(args, "--mode", "transaction_per_row").toUpperCase());
            Side side = Side.valueOf(stringArg(args, "--side", "mixed").toUpperCase());
            return new Config(
                    stringArg(args, "--jdbc-url", DEFAULT_JDBC_URL),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--market-id", "WALLET_ORDER_SUBMISSION_DB_CEILING_" + UUID.randomUUID()),
                    intArg(args, "--events", 10_000),
                    intArg(args, "--workers", 16),
                    intArg(args, "--batch-size", 100),
                    intArg(args, "--seed-balance", 1_000_000),
                    booleanArg(args, "--seed-wallets", true),
                    mode,
                    side);
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

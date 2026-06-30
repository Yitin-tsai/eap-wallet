package com.eap.eap_wallet.loadtest;

import com.eap.common.event.OrderMatchedEvent;
import com.eap.eap_wallet.EapWalletApplication;
import com.eap.eap_wallet.application.MatchedOrderListener;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

public class WalletMatchedDbLoadGenerator {

    private static final String MARKET_ID = "WALLET_MATCHED_DB_LOAD";
    private static final int PRICE = 100;
    private static final int AMOUNT = 1;

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EapWalletApplication.class)
                .profiles("loadtest")
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.rabbitmq.listener.simple.auto-startup=false",
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/eapdb",
                        "spring.datasource.username=admin",
                        "spring.datasource.password=admin123",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.liquibase.enabled=true",
                        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
                        "eap.wallet.outbox-relay.enabled=false",
                        "logging.level.com.eap.eap_wallet.application.MatchedOrderListener=WARN",
                        "logging.level.org.springframework.amqp=WARN",
                        "logging.level.org.hibernate=WARN")
                .run()) {

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            MatchedOrderListener matchedOrderListener = context.getBean(MatchedOrderListener.class);

            if (config.truncate()) {
                truncateWalletTestData(jdbcTemplate);
            }

            System.out.printf(
                    "seeding %d buyer/seller wallet pairs (%d wallets)%n",
                    config.events(), config.events() * 2);
            List<Pair> pairs = seedWalletPairs(jdbcTemplate, config.events());

            System.out.printf(
                    "settling %d matches, workers=%d%n",
                    config.events(), config.workers());
            Result result = runSettlementLoad(config, pairs, matchedOrderListener);

            long walletRows = count(jdbcTemplate, "SELECT count(*) FROM wallet_service.wallets");
            long lockedCurrency = count(jdbcTemplate, "SELECT COALESCE(sum(locked_currency), 0) FROM wallet_service.wallets");
            long lockedAmount = count(jdbcTemplate, "SELECT COALESCE(sum(locked_amount), 0) FROM wallet_service.wallets");
            long buyerAvailableAmount = count(jdbcTemplate,
                    "SELECT COALESCE(sum(available_amount), 0) FROM wallet_service.wallets");
            long sellerAvailableCurrency = count(jdbcTemplate,
                    "SELECT COALESCE(sum(available_currency), 0) FROM wallet_service.wallets");

            printResult(config, result, walletRows, lockedCurrency, lockedAmount, buyerAvailableAmount, sellerAvailableCurrency);

            require(result.failures() == 0, "wallet matched DB load should have no failures");
            require(walletRows == config.events() * 2L, "each match should use one buyer and one seller wallet");
            require(lockedCurrency == 0, "buyer locked currency should be released");
            require(lockedAmount == 0, "seller locked amount should be released");
            require(buyerAvailableAmount == config.events() * (long) AMOUNT, "buyers should receive matched energy amount");
            require(sellerAvailableCurrency == config.events() * (long) PRICE * AMOUNT, "sellers should receive deal currency");
        }
    }

    private static List<Pair> seedWalletPairs(JdbcTemplate jdbcTemplate, int events) {
        List<Pair> pairs = new ArrayList<>(events);
        List<Object[]> batchArgs = new ArrayList<>(events * 2);
        for (int i = 0; i < events; i++) {
            UUID buyerId = UUID.randomUUID();
            UUID sellerId = UUID.randomUUID();
            pairs.add(new Pair(i + 1, buyerId, sellerId, UUID.randomUUID(), UUID.randomUUID()));

            batchArgs.add(new Object[] {
                    buyerId,
                    0,
                    0,
                    0,
                    PRICE * AMOUNT
            });
            batchArgs.add(new Object[] {
                    sellerId,
                    0,
                    AMOUNT,
                    0,
                    0
            });
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO wallet_service.wallets (
                    user_id,
                    available_amount,
                    locked_amount,
                    available_currency,
                    locked_currency,
                    update_time,
                    version
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
                """, batchArgs);
        return pairs;
    }

    private static Result runSettlementLoad(
            Config config,
            List<Pair> pairs,
            MatchedOrderListener matchedOrderListener) throws InterruptedException {
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(pairs.size());
        ExecutorService executor = Executors.newFixedThreadPool(config.workers());
        Semaphore inFlight = new Semaphore(config.workers() * 2);
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(pairs.size()));

        long started = System.nanoTime();
        for (Pair pair : pairs) {
            inFlight.acquire();
            executor.execute(() -> {
                try {
                    long itemStarted = System.nanoTime();
                    matchedOrderListener.handleOrderMatched(matched(pair));
                    latenciesNanos.add(System.nanoTime() - itemStarted);
                    processed.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                    if (failures.get() <= 10) {
                        System.err.printf("wallet settlement failed: matchId=%d, error=%s%n",
                                pair.matchId(), e.getMessage());
                    }
                } finally {
                    inFlight.release();
                    done.countDown();
                }
            });
        }

        done.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
        Collections.sort(sortedLatencies);
        return new Result(
                processed.get(),
                failures.get(),
                elapsedSeconds,
                percentileMillis(sortedLatencies, 0.50),
                percentileMillis(sortedLatencies, 0.95),
                percentileMillis(sortedLatencies, 0.99));
    }

    private static OrderMatchedEvent matched(Pair pair) {
        return OrderMatchedEvent.builder()
                .matchId(pair.matchId())
                .buyerId(pair.buyerId())
                .sellerId(pair.sellerId())
                .buyerOrderId(pair.buyOrderId())
                .sellerOrderId(pair.sellOrderId())
                .marketId(MARKET_ID)
                .buyerMarketSequence((long) pair.matchId() * 2L - 1L)
                .sellerMarketSequence((long) pair.matchId() * 2L)
                .originBuyerPrice(PRICE)
                .originSellerPrice(PRICE)
                .dealPrice(PRICE)
                .amount(AMOUNT)
                .matchedAt(LocalDateTime.now())
                .orderType("BUY")
                .build();
    }

    private static void truncateWalletTestData(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    wallet_service.outbox,
                    wallet_service.order_submission_idempotency,
                    wallet_service.settlement_idempotency,
                    wallet_service.wallets
                RESTART IDENTITY CASCADE
                """);
    }

    private static long count(JdbcTemplate jdbcTemplate, String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000.0;
    }

    private static void printResult(
            Config config,
            Result result,
            long walletRows,
            long lockedCurrency,
            long lockedAmount,
            long buyerAvailableAmount,
            long sellerAvailableCurrency) {
        System.out.println("{");
        System.out.printf("  \"mode\": \"walletMatchedDb\",%n");
        System.out.printf("  \"matches\": %d,%n", config.events());
        System.out.printf("  \"workers\": %d,%n", config.workers());
        System.out.printf("  \"processed\": %d,%n", result.processed());
        System.out.printf("  \"failures\": %d,%n", result.failures());
        System.out.printf("  \"elapsedSeconds\": %.2f,%n", result.elapsedSeconds());
        System.out.printf("  \"walletMatchedDbTps\": %.2f,%n", result.processed() / Math.max(result.elapsedSeconds(), 0.001));
        System.out.printf("  \"p50Ms\": %.2f,%n", result.p50Ms());
        System.out.printf("  \"p95Ms\": %.2f,%n", result.p95Ms());
        System.out.printf("  \"p99Ms\": %.2f,%n", result.p99Ms());
        System.out.printf("  \"walletRows\": %d,%n", walletRows);
        System.out.printf("  \"lockedCurrency\": %d,%n", lockedCurrency);
        System.out.printf("  \"lockedAmount\": %d,%n", lockedAmount);
        System.out.printf("  \"buyerAvailableAmount\": %d,%n", buyerAvailableAmount);
        System.out.printf("  \"sellerAvailableCurrency\": %d%n", sellerAvailableCurrency);
        System.out.println("}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Pair(
            int matchId,
            UUID buyerId,
            UUID sellerId,
            UUID buyOrderId,
            UUID sellOrderId) {
    }

    private record Result(
            int processed,
            int failures,
            double elapsedSeconds,
            double p50Ms,
            double p95Ms,
            double p99Ms) {
    }

    private record Config(int events, int workers, boolean truncate) {
        private static Config from(String[] args) {
            return new Config(
                    intArg(args, "--events", 1_000),
                    intArg(args, "--workers", 16),
                    booleanArg(args, "--truncate", true));
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

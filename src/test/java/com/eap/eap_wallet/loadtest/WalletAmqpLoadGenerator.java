package com.eap.eap_wallet.loadtest;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.OrderSubmittedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

public class WalletAmqpLoadGenerator {

    private static final int DEFAULT_USERS = 500;
    private static final int DEFAULT_EVENTS = 10_000;
    private static final int DEFAULT_TPS = 500;
    private static final int DEFAULT_WORKERS = 32;
    private static final BigDecimal DEFAULT_DUPLICATE_RATIO = new BigDecimal("0.10");
    private static final String DEFAULT_WALLET_URL = "http://localhost:8081/eap-wallet";
    private static final String DEFAULT_RABBIT_HOST = "localhost";
    private static final int DEFAULT_RABBIT_PORT = 5672;
    private static final String DEFAULT_RABBIT_USER = "admin";
    private static final String DEFAULT_RABBIT_PASSWORD = "admin123";

    public static void main(String[] args) throws Exception {
        Config config = Config.from(args);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        System.out.printf("registering %d users through %s%n", config.users(), config.walletUrl());
        List<UUID> userIds = registerUsers(config, httpClient, objectMapper);
        System.out.printf("registered users: %d%n", userIds.size());

        List<OrderSubmittedEvent> events = buildEvents(config, userIds);

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(config.rabbitHost(), config.rabbitPort());
        connectionFactory.setUsername(config.rabbitUser());
        connectionFactory.setPassword(config.rabbitPassword());
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        rabbitTemplate.setMandatory(true);

        AtomicInteger published = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicLong nextSendAtNanos = new AtomicLong(System.nanoTime());
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(config.tps(), 1);
        CountDownLatch done = new CountDownLatch(events.size());
        ExecutorService executor = Executors.newFixedThreadPool(config.workers());
        long started = System.nanoTime();

        System.out.printf(
                "publishing %d events through AMQP, targetTps=%d, workers=%d, duplicateRatio=%s%n",
                events.size(),
                config.tps(),
                config.workers(),
                config.duplicateRatio());

        for (OrderSubmittedEvent event : events) {
            executor.execute(() -> {
                try {
                    throttle(nextSendAtNanos, intervalNanos);
                    rabbitTemplate.convertAndSend(
                            RabbitMQConstants.ORDER_EXCHANGE,
                            RabbitMQConstants.ORDER_SUBMITTED_KEY,
                            event);
                    published.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                    System.err.printf("publish failed: orderId=%s, error=%s%n", event.getOrderId(), e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        connectionFactory.destroy();

        double elapsedSeconds = Duration.ofNanos(System.nanoTime() - started).toNanos() / 1_000_000_000.0;
        double actualTps = published.get() / Math.max(elapsedSeconds, 0.001);

        System.out.println("{");
        System.out.printf("  \"events\": %d,%n", events.size());
        System.out.printf("  \"published\": %d,%n", published.get());
        System.out.printf("  \"failures\": %d,%n", failures.get());
        System.out.printf("  \"elapsedSeconds\": %.2f,%n", elapsedSeconds);
        System.out.printf("  \"actualTps\": %.2f%n", actualTps);
        System.out.println("}");
    }

    private static List<UUID> registerUsers(Config config, HttpClient httpClient, ObjectMapper objectMapper)
            throws IOException, InterruptedException {
        List<UUID> userIds = new ArrayList<>(config.users());
        for (int i = 0; i < config.users(); i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.walletUrl() + "/v1/wallet/register"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "register failed: status=" + response.statusCode() + ", body=" + response.body());
            }

            JsonNode body = objectMapper.readTree(response.body());
            if (!body.path("success").asBoolean(false)) {
                throw new IllegalStateException("register failed: " + response.body());
            }
            userIds.add(UUID.fromString(body.path("userId").asText()));
        }
        return userIds;
    }

    private static List<OrderSubmittedEvent> buildEvents(Config config, List<UUID> userIds) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<OrderSubmittedEvent> events = new ArrayList<>(config.events());
        List<UUID> duplicatePool = new ArrayList<>(config.events());

        for (int i = 0; i < config.events(); i++) {
            boolean duplicate = !duplicatePool.isEmpty()
                    && BigDecimal.valueOf(random.nextDouble()).compareTo(config.duplicateRatio()) < 0;
            UUID orderId = duplicate
                    ? duplicatePool.get(random.nextInt(duplicatePool.size()))
                    : UUID.randomUUID();
            if (!duplicate) {
                duplicatePool.add(orderId);
            }

            boolean buy = random.nextBoolean();
            int price = random.nextInt(10, 101);
            int amount = random.nextInt(1, 6);

            events.add(OrderSubmittedEvent.builder()
                    .orderId(orderId)
                    .userId(userIds.get(random.nextInt(userIds.size())))
                    .marketId("LOAD_TEST")
                    .marketSequence((long) i + 1)
                    .price(price)
                    .amount(amount)
                    .orderType(buy ? "BUY" : "SELL")
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        return events;
    }

    private static void throttle(AtomicLong nextSendAtNanos, long intervalNanos) {
        long scheduledAt = nextSendAtNanos.getAndAdd(intervalNanos);
        long waitNanos = scheduledAt - System.nanoTime();
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while throttling", e);
            }
        }
    }

    private record Config(
            int users,
            int events,
            int tps,
            int workers,
            BigDecimal duplicateRatio,
            String walletUrl,
            String rabbitHost,
            int rabbitPort,
            String rabbitUser,
            String rabbitPassword) {

        private static Config from(String[] args) {
            int users = intArg(args, "--users", DEFAULT_USERS);
            int events = intArg(args, "--events", DEFAULT_EVENTS);
            int tps = intArg(args, "--tps", DEFAULT_TPS);
            int workers = intArg(args, "--workers", DEFAULT_WORKERS);
            BigDecimal duplicateRatio = decimalArg(args, "--duplicate-ratio", DEFAULT_DUPLICATE_RATIO);
            String walletUrl = stringArg(args, "--wallet-url", DEFAULT_WALLET_URL);
            String rabbitHost = stringArg(args, "--rabbit-host", DEFAULT_RABBIT_HOST);
            int rabbitPort = intArg(args, "--rabbit-port", DEFAULT_RABBIT_PORT);
            String rabbitUser = stringArg(args, "--rabbit-user", DEFAULT_RABBIT_USER);
            String rabbitPassword = stringArg(args, "--rabbit-pass", DEFAULT_RABBIT_PASSWORD);

            if (duplicateRatio.compareTo(BigDecimal.ZERO) < 0 || duplicateRatio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("--duplicate-ratio must be between 0 and 1");
            }
            return new Config(
                    users,
                    events,
                    tps,
                    workers,
                    duplicateRatio,
                    walletUrl,
                    rabbitHost,
                    rabbitPort,
                    rabbitUser,
                    rabbitPassword);
        }

        private static String stringArg(String[] args, String name, String defaultValue) {
            for (int i = 0; i < args.length - 1; i++) {
                if (name.equals(args[i])) {
                    return args[i + 1];
                }
            }
            return defaultValue;
        }

        private static int intArg(String[] args, String name, int defaultValue) {
            return Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static BigDecimal decimalArg(String[] args, String name, BigDecimal defaultValue) {
            return new BigDecimal(stringArg(args, name, defaultValue.toPlainString()));
        }
    }
}

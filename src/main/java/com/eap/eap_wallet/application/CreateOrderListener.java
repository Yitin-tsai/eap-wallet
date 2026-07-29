package com.eap.eap_wallet.application;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.common.event.OrderSubmittedEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderFailedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.eap.common.constants.RabbitMQConstants.*;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@Slf4j
public class CreateOrderListener {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WalletMetrics walletMetrics;

    @RabbitListener(
            queues = WALLET_ORDER_SUBMITTED_QUEUE,
            concurrency = "${eap.wallet.listeners.order-submitted.concurrency:8}")
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        long startedAt = System.nanoTime();
        walletMetrics.orderSubmittedConsumed();
        try {
            log.info("收到 OrderSubmittedEvent: orderId={}, userId={}, type={}, price={}, amount={}",
                     event.getOrderId(), event.getUserId(), event.getOrderType(),
                     event.getPrice(), event.getAmount());

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            int maxRetries = 3;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                long transactionStartedAt = System.nanoTime();
                long[] callbackStartedAt = new long[1];
                long[] bodyCompletedAt = new long[1];
                try {
                    txTemplate.executeWithoutResult(status -> {
                        callbackStartedAt[0] = System.nanoTime();
                        walletMetrics.recordOrderSubmittedTransactionBeforeCallback(
                                Duration.ofNanos(callbackStartedAt[0] - transactionStartedAt));
                        long bodyStartedAt = System.nanoTime();
                        try {
                        ReservationPayloads payloads = reservationPayloads(event);
                        ReservationOutcome outcome = reserveOrderSubmitted(event, payloads);
                        if (outcome.claimed() == 0) {
                            walletMetrics.orderSubmittedDuplicateSkipped();
                            log.info("Duplicate OrderSubmittedEvent skipped: orderId={}", event.getOrderId());
                            return;
                        }
                        if (outcome.outboxInserted() != 1) {
                            throw new IllegalStateException("Wallet reservation did not insert exactly one outbox row: "
                                    + outcome);
                        }
                        if (outcome.reserved() == 1) {
                            log.info("訂單處理完成: orderId={}", event.getOrderId());
                            return;
                        }
                        if (outcome.walletExists() == 0) {
                            log.warn("找不到使用者錢包: {}", event.getUserId());
                        } else {
                            log.warn("資產不足: orderId={}, userId={}, reason={}",
                                    event.getOrderId(), event.getUserId(), payloads.insufficientReason());
                        }
                        } finally {
                            bodyCompletedAt[0] = System.nanoTime();
                            walletMetrics.recordOrderSubmittedTransactionBody(
                                    Duration.ofNanos(bodyCompletedAt[0] - bodyStartedAt));
                        }
                    });
                    break; // success, exit retry loop
                } catch (DataIntegrityViolationException e) {
                    log.error("OrderSubmittedEvent idempotency claim failed unexpectedly: orderId={}",
                            event.getOrderId(), e);
                    throw e;
                } catch (CannotAcquireLockException e) {
                    if (attempt == maxRetries) {
                        log.error("訂單處理失敗，錢包保留交易衝突達 {} 次上限: orderId={}, userId={}",
                                maxRetries, event.getOrderId(), event.getUserId(), e);
                        throw e;
                    }
                    walletMetrics.optimisticLockRetry();
                    log.warn("錢包保留交易衝突，重試 {}/{}: orderId={}, userId={}",
                            attempt, maxRetries, event.getOrderId(), event.getUserId());
                } finally {
                    long transactionCompletedAt = System.nanoTime();
                    if (bodyCompletedAt[0] > 0) {
                        walletMetrics.recordOrderSubmittedTransactionAfterBody(
                                Duration.ofNanos(transactionCompletedAt - bodyCompletedAt[0]));
                    }
                    walletMetrics.recordOrderSubmittedTransaction(
                            Duration.ofNanos(transactionCompletedAt - transactionStartedAt));
                }
            }
        } finally {
            walletMetrics.recordOrderSubmittedProcessing(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private ReservationPayloads reservationPayloads(OrderSubmittedEvent event) {
        try {
            String orderType = event.getOrderType();
            if (!"BUY".equals(orderType) && !"SELL".equals(orderType)) {
                log.error("未知的訂單類型: {}", orderType);
                return ReservationPayloads.invalidType(
                        objectMapper.writeValueAsString(orderFailedEvent(event, "訂單類型錯誤")));
            }

            OrderConfirmedEvent orderConfirmedEvent = OrderConfirmedEvent.builder()
                    .orderId(event.getOrderId())
                    .userId(event.getUserId())
                    .marketId(event.getMarketId())
                    .marketSequence(event.getMarketSequence())
                    .price(event.getPrice())
                    .amount(event.getAmount())
                    .orderType(event.getOrderType())
                    .createdAt(event.getCreatedAt())
                    .build();
            String insufficientReason = "BUY".equals(orderType) ? "餘額不足" : "可用電量不足";
            return new ReservationPayloads(
                    objectMapper.writeValueAsString(orderConfirmedEvent),
                    objectMapper.writeValueAsString(orderFailedEvent(event, insufficientReason)),
                    objectMapper.writeValueAsString(orderFailedEvent(event, "錢包不存在")),
                    insufficientReason,
                    false);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize wallet reservation event", e);
        }
    }

    private OrderFailedEvent orderFailedEvent(OrderSubmittedEvent originalEvent, String reason) {
        String failureType = reason.contains("餘額") ? "INSUFFICIENT_BALANCE" :
                           reason.contains("電量") ? "INSUFFICIENT_AMOUNT" :
                           reason.contains("類型") ? "INVALID_ORDER_TYPE" : "WALLET_NOT_FOUND";

        return OrderFailedEvent.builder()
                .orderId(originalEvent.getOrderId())
                .userId(originalEvent.getUserId())
                .reason(reason)
                .failureType(failureType)
                .failedAt(LocalDateTime.now())
                .build();
    }

    private ReservationOutcome reserveOrderSubmitted(OrderSubmittedEvent event, ReservationPayloads payloads) {
        int requiredCurrency = event.getAmount() * event.getPrice();
        String orderType = event.getOrderType();
        long cteStartedAt = System.nanoTime();
        try {
            return jdbcTemplate.queryForObject("""
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
                                WHEN ? THEN ?
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
                    """, (rs, rowNum) -> new ReservationOutcome(
                            rs.getInt("claimed"),
                            rs.getInt("wallet_exists"),
                            rs.getInt("reserved"),
                            rs.getInt("outbox_inserted")),
                    event.getOrderId(), event.getUserId(),
                    orderType, requiredCurrency,
                    orderType, event.getAmount(),
                    event.getUserId(),
                    orderType, requiredCurrency,
                    orderType, requiredCurrency,
                    orderType, event.getAmount(),
                    orderType, event.getAmount(),
                    ORDER_CONFIRMED_KEY, ORDER_FAILED_KEY,
                    payloads.invalidType(),
                    payloads.insufficientPayload(),
                    payloads.confirmedPayload(),
                    payloads.walletMissingPayload(),
                    payloads.insufficientPayload());
        } finally {
            walletMetrics.recordOrderSubmittedReservationCte(
                    Duration.ofNanos(System.nanoTime() - cteStartedAt));
        }
    }

    record ReservationPayloads(
            String confirmedPayload,
            String insufficientPayload,
            String walletMissingPayload,
            String insufficientReason,
            boolean invalidType) {

        static ReservationPayloads invalidType(String payload) {
            return new ReservationPayloads(payload, payload, payload, "訂單類型錯誤", true);
        }
    }

    record ReservationOutcome(int claimed, int walletExists, int reserved, int outboxInserted) {
    }

}

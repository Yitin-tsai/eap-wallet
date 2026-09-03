package com.eap.eap_wallet.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.common.event.OrderFailedEvent;
import com.eap.common.event.OrderSubmittedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

import static com.eap.common.constants.RabbitMQConstants.ORDER_ASSET_RESERVATION_SUCCEEDED_KEY;
import static com.eap.common.constants.RabbitMQConstants.ORDER_FAILED_KEY;

@Component
@RequiredArgsConstructor
@Slf4j
public class WalletOrderReservationProcessor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WalletMetrics walletMetrics;

    public ReservationOutcome reserve(OrderSubmittedEvent event) {
        validate(event);
        ReservationPayloads payloads = reservationPayloads(event);
        int requiredCurrency = Math.multiplyExact(event.getAmount(), event.getPrice());
        String orderType = event.getOrderType();
        long cteStartedAt = System.nanoTime();
        try {
            ReservationOutcome outcome = jdbcTemplate.queryForObject("""
                    WITH claimed AS (
                        INSERT INTO wallet_service.order_submission_idempotency(order_id, user_id, recorded_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (order_id) DO NOTHING
                        RETURNING order_id
                    ),
                    wallet_state AS (
                        SELECT w.user_id
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
                          AND CASE
                              WHEN ? = 'BUY' THEN w.available_currency >= ?
                              WHEN ? = 'SELL' THEN w.available_amount >= ?
                              ELSE false
                          END
                        RETURNING w.user_id
                    ),
                    outbox_inserted AS (
                        INSERT INTO wallet_service.outbox
                            (event_type, routing_key, payload, status,
                             created_at, attempt_count, next_retry_at, updated_at)
                        SELECT
                            CASE WHEN EXISTS (SELECT 1 FROM reserved)
                                THEN 'OrderAssetReservationSucceededEvent' ELSE 'OrderFailedEvent' END,
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
                    event.getUserId(),
                    orderType, requiredCurrency,
                    orderType, requiredCurrency,
                    orderType, event.getAmount(),
                    orderType, event.getAmount(),
                    orderType, requiredCurrency,
                    orderType, event.getAmount(),
                    ORDER_ASSET_RESERVATION_SUCCEEDED_KEY, ORDER_FAILED_KEY,
                    payloads.invalidType(),
                    payloads.insufficientPayload(),
                    payloads.confirmedPayload(),
                    payloads.walletMissingPayload(),
                    payloads.insufficientPayload());
            if (outcome == null) {
                throw new IllegalStateException("Wallet reservation transaction returned no outcome");
            }
            if (outcome.claimed() == 0) {
                log.debug("Wallet reservation already applied: orderId={}", event.getOrderId());
                return outcome;
            }
            if (outcome.outboxInserted() != 1) {
                throw new IllegalStateException("Wallet reservation did not insert exactly one outbox row: " + outcome);
            }
            return outcome;
        } finally {
            walletMetrics.recordOrderSubmittedReservationCte(
                    Duration.ofNanos(System.nanoTime() - cteStartedAt));
        }
    }

    private void validate(OrderSubmittedEvent event) {
        if (event == null || event.getOrderId() == null || event.getUserId() == null) {
            throw new IllegalArgumentException("Order submission identifiers are required");
        }
        if (event.getPrice() == null || event.getPrice() <= 0
                || event.getAmount() == null || event.getAmount() <= 0) {
            throw new IllegalArgumentException("Order price and amount must be positive");
        }
    }

    private ReservationPayloads reservationPayloads(OrderSubmittedEvent event) {
        try {
            String orderType = event.getOrderType();
            if (!"BUY".equals(orderType) && !"SELL".equals(orderType)) {
                String invalid = objectMapper.writeValueAsString(orderFailedEvent(event, "訂單類型錯誤"));
                return ReservationPayloads.invalidType(invalid);
            }
            OrderAssetReservationSucceededEvent confirmed = OrderAssetReservationSucceededEvent.builder()
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
                    objectMapper.writeValueAsString(confirmed),
                    objectMapper.writeValueAsString(orderFailedEvent(event, insufficientReason)),
                    objectMapper.writeValueAsString(orderFailedEvent(event, "錢包不存在")),
                    false);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize Wallet reservation result", e);
        }
    }

    private OrderFailedEvent orderFailedEvent(OrderSubmittedEvent event, String reason) {
        String failureType = reason.contains("餘額") ? "INSUFFICIENT_BALANCE"
                : reason.contains("電量") ? "INSUFFICIENT_AMOUNT"
                : reason.contains("類型") ? "INVALID_ORDER_TYPE"
                : "WALLET_NOT_FOUND";
        return OrderFailedEvent.builder()
                .orderId(event.getOrderId())
                .userId(event.getUserId())
                .reason(reason)
                .failureType(failureType)
                .failedAt(LocalDateTime.now())
                .build();
    }

    record ReservationPayloads(
            String confirmedPayload,
            String insufficientPayload,
            String walletMissingPayload,
            boolean invalidType) {

        static ReservationPayloads invalidType(String payload) {
            return new ReservationPayloads(payload, payload, payload, true);
        }
    }

    public record ReservationOutcome(int claimed, int walletExists, int reserved, int outboxInserted) {
    }
}

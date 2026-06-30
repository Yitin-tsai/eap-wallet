package com.eap.eap_wallet.application;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.OrderSubmissionIdempotencyRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
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
    private WalletRepository walletRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OrderSubmissionIdempotencyRepository orderSubmissionIdempotencyRepository;

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
                try {
                    txTemplate.executeWithoutResult(status -> {
                        if (!claimOrderSubmitted(event)) {
                            walletMetrics.orderSubmittedDuplicateSkipped();
                            log.info("Duplicate OrderSubmittedEvent skipped: orderId={}", event.getOrderId());
                            return;
                        }

                        String orderType = event.getOrderType();
                        if ("BUY".equals(orderType)) {
                            int requiredCurrency = event.getAmount() * event.getPrice();
                            if (!reserveBuy(event, requiredCurrency)) {
                                return;
                            }
                        } else if ("SELL".equals(orderType)) {
                            if (!reserveSell(event)) {
                                return;
                            }
                        } else {
                            log.error("未知的訂單類型: {}", orderType);
                            sendOrderFailedEvent(event, "訂單類型錯誤");
                            return;
                        }

                        try {
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
                            long outboxWriteStartedAt = System.nanoTime();
                            try {
                                String payload = objectMapper.writeValueAsString(orderConfirmedEvent);
                                outboxRepository.save(new OutboxEntity("OrderConfirmedEvent", ORDER_CONFIRMED_KEY, payload));
                            } finally {
                                walletMetrics.recordOrderSubmittedOutboxWrite(
                                        Duration.ofNanos(System.nanoTime() - outboxWriteStartedAt));
                            }
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to serialize OrderConfirmedEvent", e);
                        }

                        log.info("訂單處理完成: orderId={}", event.getOrderId());
                    });
                    break; // success, exit retry loop
                } catch (DataIntegrityViolationException e) {
                    log.error("OrderSubmittedEvent idempotency claim failed unexpectedly: orderId={}",
                            event.getOrderId(), e);
                    throw e;
                } catch (ObjectOptimisticLockingFailureException e) {
                    if (attempt == maxRetries) {
                        log.error("訂單處理失敗，optimistic lock 衝突達 {} 次上限: orderId={}, userId={}",
                                maxRetries, event.getOrderId(), event.getUserId(), e);
                        throw e;
                    }
                    walletMetrics.optimisticLockRetry();
                    log.warn("Optimistic lock 衝突，重試 {}/{}: orderId={}, userId={}",
                            attempt, maxRetries, event.getOrderId(), event.getUserId());
                } finally {
                    walletMetrics.recordOrderSubmittedTransaction(
                            Duration.ofNanos(System.nanoTime() - transactionStartedAt));
                }
            }
        } finally {
            walletMetrics.recordOrderSubmittedProcessing(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private boolean reserveBuy(OrderSubmittedEvent event, int requiredCurrency) {
        long walletUpdateStartedAt = System.nanoTime();
        int updated;
        try {
            updated = walletRepository.reserveCurrencyForBuy(event.getUserId(), requiredCurrency);
        } finally {
            walletMetrics.recordOrderSubmittedWalletLookup(
                    Duration.ofNanos(System.nanoTime() - walletUpdateStartedAt));
        }
        if (updated == 1) {
            log.info("買單鎖定貨幣: userId={}, 鎖定金額={}", event.getUserId(), requiredCurrency);
            return true;
        }
        sendReservationFailed(event, "餘額不足");
        return false;
    }

    private boolean reserveSell(OrderSubmittedEvent event) {
        long walletUpdateStartedAt = System.nanoTime();
        int updated;
        try {
            updated = walletRepository.reserveAmountForSell(event.getUserId(), event.getAmount());
        } finally {
            walletMetrics.recordOrderSubmittedWalletLookup(
                    Duration.ofNanos(System.nanoTime() - walletUpdateStartedAt));
        }
        if (updated == 1) {
            log.info("賣單鎖定電量: userId={}, 鎖定數量={}", event.getUserId(), event.getAmount());
            return true;
        }
        sendReservationFailed(event, "可用電量不足");
        return false;
    }

    private void sendReservationFailed(OrderSubmittedEvent event, String insufficientReason) {
        if (!walletRepository.existsByUserId(event.getUserId())) {
            log.warn("找不到使用者錢包: {}", event.getUserId());
            sendOrderFailedEvent(event, "錢包不存在");
            return;
        }
        log.warn("資產不足: orderId={}, userId={}, reason={}",
                event.getOrderId(), event.getUserId(), insufficientReason);
        sendOrderFailedEvent(event, insufficientReason);
    }

    private void sendOrderFailedEvent(OrderSubmittedEvent originalEvent, String reason) {
        String failureType = reason.contains("餘額") ? "INSUFFICIENT_BALANCE" :
                           reason.contains("電量") ? "INSUFFICIENT_AMOUNT" : "WALLET_NOT_FOUND";

        OrderFailedEvent failedEvent = OrderFailedEvent.builder()
                .orderId(originalEvent.getOrderId())
                .userId(originalEvent.getUserId())
                .reason(reason)
                .failureType(failureType)
                .failedAt(LocalDateTime.now())
                .build();

        try {
            long outboxWriteStartedAt = System.nanoTime();
            try {
                String payload = objectMapper.writeValueAsString(failedEvent);
                outboxRepository.save(new OutboxEntity("OrderFailedEvent", ORDER_FAILED_KEY, payload));
            } finally {
                walletMetrics.recordOrderSubmittedOutboxWrite(
                        Duration.ofNanos(System.nanoTime() - outboxWriteStartedAt));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OrderFailedEvent", e);
        }
        log.info("已發送訂單失敗通知: {} - {}", originalEvent.getOrderId(), reason);
    }

    private boolean claimOrderSubmitted(OrderSubmittedEvent event) {
        long claimStartedAt = System.nanoTime();
        try {
            return orderSubmissionIdempotencyRepository.claimOrderSubmission(
                    event.getOrderId(), event.getUserId()) == 1;
        } finally {
            walletMetrics.recordOrderSubmittedIdempotencyClaim(
                    Duration.ofNanos(System.nanoTime() - claimStartedAt));
        }
    }
}

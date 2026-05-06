package com.eap.eap_wallet.application;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderSubmittedEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderFailedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.eap.common.constants.RabbitMQConstants.*;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Component
@Slf4j
public class CreateOrderListener {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @RabbitListener(queues = WALLET_ORDER_SUBMITTED_QUEUE)
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        log.info("收到 OrderSubmittedEvent: orderId={}, userId={}, type={}, price={}, amount={}",
                 event.getOrderId(), event.getUserId(), event.getOrderType(),
                 event.getPrice(), event.getAmount());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                txTemplate.executeWithoutResult(status -> {
                    WalletEntity wallet = walletRepository.findByUserId(event.getUserId());
                    if (wallet == null) {
                        log.warn("找不到使用者錢包: {}", event.getUserId());
                        sendOrderFailedEvent(event, "錢包不存在");
                        return;
                    }

                    String orderType = event.getOrderType();

                    if ("BUY".equals(orderType)) {
                        int requiredCurrency = event.getAmount() * event.getPrice();
                        if (requiredCurrency > wallet.getAvailableCurrency()) {
                            log.warn("買單餘額不足: userId={}, 需要={}, 可用={}",
                                     event.getUserId(), requiredCurrency, wallet.getAvailableCurrency());
                            sendOrderFailedEvent(event, "餘額不足");
                            return;
                        }
                    } else if ("SELL".equals(orderType)) {
                        if (event.getAmount() > wallet.getAvailableAmount()) {
                            log.warn("賣單電量不足: userId={}, 需要={}, 可用={}",
                                     event.getUserId(), event.getAmount(), wallet.getAvailableAmount());
                            sendOrderFailedEvent(event, "可用電量不足");
                            return;
                        }
                    } else {
                        log.error("未知的訂單類型: {}", orderType);
                        sendOrderFailedEvent(event, "訂單類型錯誤");
                        return;
                    }

                    lockAsset(wallet, event);

                    try {
                        OrderConfirmedEvent orderConfirmedEvent = OrderConfirmedEvent.builder()
                                .orderId(event.getOrderId())
                                .userId(event.getUserId())
                                .price(event.getPrice())
                                .amount(event.getAmount())
                                .orderType(event.getOrderType())
                                .createdAt(event.getCreatedAt())
                                .build();
                        String payload = objectMapper.writeValueAsString(orderConfirmedEvent);
                        outboxRepository.save(new OutboxEntity("OrderConfirmedEvent", ORDER_CONFIRMED_KEY, payload));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to serialize OrderConfirmedEvent", e);
                    }

                    log.info("訂單處理完成: orderId={}", event.getOrderId());
                });
                break; // success, exit retry loop
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == maxRetries) {
                    log.error("訂單處理失敗，optimistic lock 衝突達 {} 次上限: orderId={}, userId={}",
                            maxRetries, event.getOrderId(), event.getUserId(), e);
                    throw e;
                }
                log.warn("Optimistic lock 衝突，重試 {}/{}: orderId={}, userId={}",
                        attempt, maxRetries, event.getOrderId(), event.getUserId());
            }
        }
    }

    private void lockAsset(WalletEntity wallet, OrderSubmittedEvent event) {
        if ("BUY".equals(event.getOrderType())) {
            int lockCurrency = event.getPrice() * event.getAmount();
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() - lockCurrency);
            wallet.setLockedCurrency(wallet.getLockedCurrency() + lockCurrency);
            log.info("買單鎖定貨幣: userId={}, 鎖定金額={}", wallet.getUserId(), lockCurrency);
        } else if ("SELL".equals(event.getOrderType())) {
            int lockAmount = event.getAmount();
            wallet.setAvailableAmount(wallet.getAvailableAmount() - lockAmount);
            wallet.setLockedAmount(wallet.getLockedAmount() + lockAmount);
            log.info("賣單鎖定電量: userId={}, 鎖定數量={}", wallet.getUserId(), lockAmount);
        }
        walletRepository.save(wallet);
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
            String payload = objectMapper.writeValueAsString(failedEvent);
            outboxRepository.save(new OutboxEntity("OrderFailedEvent", ORDER_FAILED_KEY, payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OrderFailedEvent", e);
        }
        log.info("已發送訂單失敗通知: {} - {}", originalEvent.getOrderId(), reason);
    }
}

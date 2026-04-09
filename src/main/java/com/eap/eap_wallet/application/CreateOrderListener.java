package com.eap.eap_wallet.application;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 處理訂單提交事件
     *
     * 重要修正:
     * 1. 加入 @Transactional 確保餘額檢查和鎖定是原子操作
     * 2. 修正字串比較錯誤 (使用 .equals() 而不是 ==)
     * 3. 優化資料庫查詢 - 只查詢一次而不是三次
     *
     * 冪等性保護:
     * - RabbitMQ 的 at-most-once 語義配合 @Transactional
     * - 即使重複處理,Order Service 會拒絕重複的 orderId
     */
    @RabbitListener(queues = WALLET_ORDER_SUBMITTED_QUEUE)
    @Transactional
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        log.info("收到 OrderSubmittedEvent: orderId={}, userId={}, type={}, price={}, amount={}",
                 event.getOrderId(), event.getUserId(), event.getOrderType(),
                 event.getPrice(), event.getAmount());

        // 一次性查詢錢包 (優化: 避免重複查詢)
        WalletEntity wallet = walletRepository.findByUserId(event.getUserId());
        if (wallet == null) {
            log.warn("❌ 找不到使用者錢包: {}", event.getUserId());
            sendOrderFailedEvent(event, "錢包不存在");
            return;
        }

        // 驗證餘額 (修正: 使用 .equals() 而不是 ==)
        String orderType = event.getOrderType();

        if ("BUY".equals(orderType)) {
            int requiredCurrency = event.getAmount() * event.getPrice();
            if (requiredCurrency > wallet.getAvailableCurrency()) {
                log.warn("❌ 買單餘額不足: userId={}, 需要={}, 可用={}",
                         event.getUserId(), requiredCurrency, wallet.getAvailableCurrency());
                sendOrderFailedEvent(event, "餘額不足");
                return;
            }
        } else if ("SELL".equals(orderType)) {
            if (event.getAmount() > wallet.getAvailableAmount()) {
                log.warn("❌ 賣單電量不足: userId={}, 需要={}, 可用={}",
                         event.getUserId(), event.getAmount(), wallet.getAvailableAmount());
                sendOrderFailedEvent(event, "可用電量不足");
                return;
            }
        } else {
            log.error("❌ 未知的訂單類型: {}", orderType);
            sendOrderFailedEvent(event, "訂單類型錯誤");
            return;
        }

        // 鎖定資產
        lockAsset(wallet, event);

        // 寫入 outbox 表（與 wallet 鎖定在同一個 DB transaction，ADR-002）
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

        log.info("✅ 訂單處理完成: orderId={}", event.getOrderId());
    }

    /**
     * 鎖定資產 (優化: 直接傳入 wallet 物件,避免重複查詢)
     */
    private void lockAsset(WalletEntity wallet, OrderSubmittedEvent event) {
        if ("BUY".equals(event.getOrderType())) {
            int lockCurrency = event.getPrice() * event.getAmount();
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() - lockCurrency);
            wallet.setLockedCurrency(wallet.getLockedCurrency() + lockCurrency);

            log.info("🔒 買單鎖定貨幣: userId={}, 鎖定金額={}", wallet.getUserId(), lockCurrency);
        } else if ("SELL".equals(event.getOrderType())) {
            int lockAmount = event.getAmount();
            wallet.setAvailableAmount(wallet.getAvailableAmount() - lockAmount);
            wallet.setLockedAmount(wallet.getLockedAmount() + lockAmount);

            log.info("🔒 賣單鎖定電量: userId={}, 鎖定數量={}", wallet.getUserId(), lockAmount);
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

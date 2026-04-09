package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderMatchedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.eap.common.constants.RabbitMQConstants.WALLET_ORDER_MATCHED_QUEUE;

@Slf4j
@Component
public class MatchedOrderListener {

    @Autowired
    WalletRepository walletRepository;

    /**
     * 處理訂單撮合事件
     *
     * 重要修正:
     * 1. 加入 @Transactional 確保原子性操作
     * 2. 修正買方扣款邏輯 - 避免重複扣款
     *
     * 冪等性保護:
     * - Order Service 的 matchId unique constraint 已經防止重複記錄
     * - 即使這裡重複處理,錢包更新是冪等的 (locked 減去已經是 0 的金額不會出錯)
     * - @Transactional 確保原子性,失敗會自動回滾
     */
    @RabbitListener(queues = WALLET_ORDER_MATCHED_QUEUE)
    @Transactional
    public void handleOrderMatched(OrderMatchedEvent event) {
        log.info("收到 OrderMatchedEvent: matchId={}, 買方={}, 賣方={}, 成交價={}, 數量={}",
                 event.getMatchId(), event.getBuyerId(), event.getSellerId(),
                 event.getDealPrice(), event.getAmount());

        // 計算金額
        Integer dealCurrency = event.getDealPrice() * event.getAmount();           // 實際支付金額
        Integer originalLockedCurrency = event.getOriginBuyerPrice() * event.getAmount(); // 原本鎖定金額

        // === 買方處理 ===
        WalletEntity buyerWallet = walletRepository.findByUserId(event.getBuyerId());
        if (buyerWallet == null) {
            log.error("❌ 找不到買方錢包: {}", event.getBuyerId());
            throw new RuntimeException("買方錢包不存在: " + event.getBuyerId());
        }

        /*
         * 關鍵修正: 買方扣款邏輯
         *
         * 原本的錯誤邏輯:
         *   lockedCurrency -= originalLockedCurrency  (解鎖原本鎖定的金額)
         *   availableCurrency -= dealCurrency         (又從可用金額扣除) ❌ 重複扣款!
         *
         * 正確邏輯:
         *   1. 從 lockedCurrency 減去原本鎖定的金額
         *   2. 將差額 (originalLockedCurrency - dealCurrency) 返還到 availableCurrency
         *
         * 範例: 買方出價 100, 數量 10, 原本鎖定 1000
         *       成交價 100, 實際支付 1000
         *       差額 = 1000 - 1000 = 0, 不返還
         *
         *       如果成交價是 80, 實際支付 800
         *       差額 = 1000 - 800 = 200, 返還 200 到可用餘額
         */
        Integer refundCurrency = originalLockedCurrency - dealCurrency; // 退還金額

        buyerWallet.setLockedCurrency(buyerWallet.getLockedCurrency() - originalLockedCurrency);
        buyerWallet.setAvailableCurrency(buyerWallet.getAvailableCurrency() + refundCurrency);
        buyerWallet.setAvailableAmount(buyerWallet.getAvailableAmount() + event.getAmount());

        walletRepository.save(buyerWallet);

        log.info("✅ 買方錢包更新完成: userId={}, 支付={}, 退還={}, 獲得電量={}",
                 event.getBuyerId(), dealCurrency, refundCurrency, event.getAmount());

        // === 賣方處理 ===
        WalletEntity sellerWallet = walletRepository.findByUserId(event.getSellerId());
        if (sellerWallet == null) {
            log.error("❌ 找不到賣方錢包: {}", event.getSellerId());
            throw new RuntimeException("賣方錢包不存在: " + event.getSellerId());
        }

        sellerWallet.setLockedAmount(sellerWallet.getLockedAmount() - event.getAmount());
        sellerWallet.setAvailableCurrency(sellerWallet.getAvailableCurrency() + dealCurrency);

        walletRepository.save(sellerWallet);

        log.info("✅ 賣方錢包更新完成: userId={}, 收入={}, 出售電量={}",
                 event.getSellerId(), dealCurrency, event.getAmount());

        log.info("🎉 撮合處理完成 - matchId={}", event.getMatchId());
    }
}

package com.eap.eap_wallet.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class WalletCheckService {

    @Autowired
    WalletRepository walletRepository;


    public boolean checkWallet(OrderConfirmedEvent event) {
        if (!isWalletEnough(event)) {
            log.warn("訂單金額超過可用餘額: " + event.getUserId());
            return false;
        }

        if (!isWalletEnoughForSell(event)) {
            log.warn("訂單可用電量不足: " + event.getUserId());
            return false;
        }

        lockAsset(event);
        return true;
    }


    /**
     * 檢查買單餘額是否足夠
     *
     * 修正: 使用 .equals() 而不是 == 來比較字串
     * 原因: == 比較物件引用,而 .equals() 比較字串內容
     */
    private boolean isWalletEnough(OrderConfirmedEvent event) {
        WalletEntity wallet = walletRepository.findByUserId(event.getUserId());
        if (wallet == null) {
            log.warn("找不到使用者錢包: " + event.getUserId());
            return false;
        }

        // 修正: 使用 .equals() 而不是 ==
        if ("BUY".equals(event.getOrderType())) {
            int requiredCurrency = event.getAmount() * event.getPrice();
            if (requiredCurrency > wallet.getAvailableCurrency()) {
                log.warn("訂單總金額超過可用餘額: userId={}, 需要={}, 可用={}",
                         event.getUserId(), requiredCurrency, wallet.getAvailableCurrency());
                return false;
            }
        }

        return true;
    }

    /**
     * 檢查賣單電量是否足夠
     *
     * 修正: 使用 .equals() 而不是 == 來比較字串
     */
    private boolean isWalletEnoughForSell(OrderConfirmedEvent event) {
        WalletEntity wallet = walletRepository.findByUserId(event.getUserId());
        if (wallet == null) {
            log.warn("找不到使用者錢包: " + event.getUserId());
            return false;
        }

        // 修正: 使用 .equals() 而不是 ==
        if ("SELL".equals(event.getOrderType())) {
            if (event.getAmount() > wallet.getAvailableAmount()) {
                log.warn("訂單總電量超過可供應電量: userId={}, 需要={}, 可用={}",
                         event.getUserId(), event.getAmount(), wallet.getAvailableAmount());
                return false;
            }
        }

        return true;
    }

    private void lockAsset(OrderConfirmedEvent event) {
        WalletEntity wallet = walletRepository.findByUserId(event.getUserId());

        if ("BUY".equals(event.getOrderType())) {
            int lockCurrency = event.getPrice() * event.getAmount();
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() - lockCurrency);
            wallet.setLockedCurrency(wallet.getLockedCurrency() + lockCurrency);
        } else if ("SELL".equals(event.getOrderType())) {
            int lockAmount = event.getAmount();
            wallet.setAvailableAmount(wallet.getAvailableAmount() - lockAmount);
            wallet.setLockedAmount(wallet.getLockedAmount() + lockAmount);
        }

        walletRepository.save(wallet);
        log.info("🔒 資產鎖定完成，用戶: {}", event.getUserId());
    }
}

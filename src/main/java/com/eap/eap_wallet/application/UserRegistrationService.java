package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserRegistrationService {

    @Autowired
    private WalletRepository walletRepository;

    /**
     * 創建新用戶錢包，返回用戶 ID
     * @return 新創建的用戶 UUID
     */
    public UUID createNewUserWallet() {
        UUID newUserId = UUID.randomUUID();
        
        // 檢查是否已存在（極小概率）
        while (walletRepository.findByUserId(newUserId) != null) {
            newUserId = UUID.randomUUID();
        }

        // 創建新錢包實體，初始餘額為 0
        WalletEntity newWallet = new WalletEntity();
        newWallet.setUserId(newUserId);
        newWallet.setAvailableAmount(10000);
        newWallet.setLockedAmount(0);
        newWallet.setAvailableCurrency(10000);
        newWallet.setLockedCurrency(0);
        newWallet.setUpdateTime(LocalDateTime.now());

        // 保存到數據庫
        WalletEntity savedWallet = walletRepository.save(newWallet);
        
        log.info("新用戶錢包創建成功: userId={}", newUserId);
        
        return savedWallet.getUserId();
    }

    /**
     * 根據用戶 ID 查詢錢包狀態
     * @param userId 用戶 UUID
     * @return 錢包實體，若不存在則返回 null
     */
    public WalletEntity getWalletByUserId(UUID userId) {
        WalletEntity wallet = walletRepository.findByUserId(userId);
        
        if (wallet == null) {
            log.warn("找不到用戶錢包: userId={}", userId);
        } else {
            log.info("查詢錢包成功: userId={}", userId);
        }
        
        return wallet;
    }

    /**
     * 檢查用戶是否存在
     * @param userId 用戶 UUID
     * @return 是否存在
     */
    public boolean userExists(UUID userId) {
        return walletRepository.findByUserId(userId) != null;
    }

    /**
     * 獲取所有已註冊用戶的 ID 列表
     * @param limit 最多返回的用戶數量，0 表示不限制
     * @return 用戶 UUID 列表
     */
    public List<UUID> listUserIds(int limit) {
        List<WalletEntity> wallets = walletRepository.findAll();

        if (limit > 0 && wallets.size() > limit) {
            wallets = wallets.subList(0, limit);
        }

        List<UUID> userIds = wallets.stream()
                .map(WalletEntity::getUserId)
                .collect(Collectors.toList());

        log.info("查詢已註冊用戶: 共 {} 位", userIds.size());
        return userIds;
    }
}

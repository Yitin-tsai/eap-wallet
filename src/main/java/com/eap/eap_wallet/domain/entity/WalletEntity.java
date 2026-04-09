package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 錢包實體
 *
 * 重要修正:
 * 1. 加入 @Version 欄位實現樂觀鎖
 * 2. 防止並發更新導致的資料不一致問題
 *
 * 樂觀鎖原理:
 * - 每次更新時 JPA 會自動檢查 version 是否匹配
 * - 如果有其他事務已經更新過,version 會不同,導致 OptimisticLockException
 * - 確保了高並發下的資料一致性
 */
@Entity
@Table(name = "wallets", schema = "wallet_service")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "available_amount", nullable = false)
    @Builder.Default
    private Integer availableAmount = 0;

    @Column(name = "locked_amount", nullable = false)
    @Builder.Default
    private Integer lockedAmount = 0;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(name = "available_currency", nullable = false)
    private Integer availableCurrency;

    @Column(name = "locked_currency", nullable = false)
    private Integer lockedCurrency;

    /**
     * 樂觀鎖版本號
     *
     * 用途:
     * - 防止 Lost Update 問題
     * - 高並發下保證資料一致性
     *
     * 範例場景:
     * 1. 事務 A 讀取錢包, version=1, balance=1000
     * 2. 事務 B 讀取錢包, version=1, balance=1000
     * 3. 事務 A 更新 balance=900, version 自動變為 2
     * 4. 事務 B 嘗試更新但 version 仍是 1, 更新失敗拋出 OptimisticLockException
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    @PreUpdate
    public void updateTimestamp() {
        this.updateTime = LocalDateTime.now();
    }
}

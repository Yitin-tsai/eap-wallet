package com.eap.eap_wallet.controller;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.dto.UserRegistrationResponse;
import com.eap.common.dto.WalletStatusResponse;
import com.eap.eap_wallet.application.UserRegistrationService;
import com.eap.eap_wallet.application.WalletCheckService;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/wallet")
public class WalletController {

    @Autowired
    WalletCheckService walletCheckService;

    @Autowired
    UserRegistrationService userRegistrationService;

    @PostMapping("/check")
    public boolean checkWallet(@RequestBody OrderConfirmedEvent event) {
        return walletCheckService.checkWallet(event);
    }

    /**
     * 用戶註冊 - 創建新錢包
     * @return 包含新用戶 ID 的響應
     */
    @PostMapping("/register")
    public ResponseEntity<UserRegistrationResponse> registerUser() {
        try {
            UUID newUserId = userRegistrationService.createNewUserWallet();
            UserRegistrationResponse response = UserRegistrationResponse.success(newUserId);
            log.info("用戶註冊成功: {}", newUserId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("用戶註冊失敗", e);
            UserRegistrationResponse response = UserRegistrationResponse.failure("註冊失敗: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 根據用戶 ID 查詢錢包狀態
     * @param userId 用戶 UUID
     * @return 錢包狀態信息
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<WalletStatusResponse> getWalletStatus(@PathVariable("userId") UUID userId) {
        try {
            WalletEntity wallet = userRegistrationService.getWalletByUserId(userId);
            
            if (wallet == null) {
                return ResponseEntity.notFound().build();
            }
            
            WalletStatusResponse response = WalletStatusResponse.success(
                wallet.getUserId(),
                wallet.getAvailableAmount(),
                wallet.getLockedAmount(),
                wallet.getAvailableCurrency(),
                wallet.getLockedCurrency(),
                null, // createdAt 不存在
                wallet.getUpdateTime()
            );
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("查詢錢包狀態失敗: userId={}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 檢查用戶是否存在
     * @param userId 用戶 UUID
     * @return 是否存在
     */
    @GetMapping("/exists/{userId}")
    public ResponseEntity<Boolean> checkUserExists(@PathVariable("userId") UUID userId) {
        try {
            boolean exists = userRegistrationService.userExists(userId);
            return ResponseEntity.ok(exists);
        } catch (Exception e) {
            log.error("檢查用戶存在性失敗: userId={}", userId, e);
            return ResponseEntity.internalServerError().body(false);
        }
    }

    /**
     * 獲取已註冊用戶列表
     * @param limit 最多返回的用戶數量，默認 10
     * @return 用戶 UUID 列表
     */
    @GetMapping("/users")
    public ResponseEntity<List<UUID>> listUsers(@RequestParam(defaultValue = "10") int limit) {
        try {
            List<UUID> userIds = userRegistrationService.listUserIds(limit);
            log.info("查詢用戶列表: 返回 {} 位用戶", userIds.size());
            return ResponseEntity.ok(userIds);
        } catch (Exception e) {
            log.error("查詢用戶列表失敗", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
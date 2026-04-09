package com.eap.eap_wallet.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletStatusResponse {
    
    @JsonProperty("userId")
    private UUID userId;
    
    @JsonProperty("availableAmount")
    private Integer availableAmount;
    
    @JsonProperty("lockedAmount")
    private Integer lockedAmount;
    
    @JsonProperty("availableCurrency")
    private Integer availableCurrency;
    
    @JsonProperty("lockedCurrency")
    private Integer lockedCurrency;
    
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
    
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
    
    @JsonProperty("success")
    private boolean success;
    
    @JsonProperty("message")
    private String message;
    
    public static WalletStatusResponse fromEntity(com.eap.eap_wallet.domain.entity.WalletEntity wallet) {
        WalletStatusResponse response = new WalletStatusResponse();
        response.setUserId(wallet.getUserId());
        response.setAvailableAmount(wallet.getAvailableAmount());
        response.setLockedAmount(wallet.getLockedAmount());
        response.setAvailableCurrency(wallet.getAvailableCurrency());
        response.setLockedCurrency(wallet.getLockedCurrency());
        response.setUpdatedAt(wallet.getUpdateTime());
        response.setSuccess(true);
        response.setMessage("錢包查詢成功");
        return response;
    }
    
    public static WalletStatusResponse notFound(UUID userId) {
        WalletStatusResponse response = new WalletStatusResponse();
        response.setUserId(userId);
        response.setSuccess(false);
        response.setMessage("找不到指定用戶的錢包");
        return response;
    }
}

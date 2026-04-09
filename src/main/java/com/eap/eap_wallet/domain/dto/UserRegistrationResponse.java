package com.eap.eap_wallet.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRegistrationResponse {
    
    @JsonProperty("userId")
    private UUID userId;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("success")
    private boolean success;
    
    public static UserRegistrationResponse success(UUID userId) {
        return new UserRegistrationResponse(userId, "用戶註冊成功", true);
    }
    
    public static UserRegistrationResponse failure(String message) {
        return new UserRegistrationResponse(null, message, false);
    }
}

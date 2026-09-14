package com.eap.eap_wallet.controller;

import com.eap.eap_wallet.application.WalletInboxInspectionService;
import com.eap.eap_wallet.domain.dto.WalletInboxMessageView;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/inbox")
@ConditionalOnProperty(name = "eap.wallet.inbox-admin.enabled", havingValue = "true")
@Profile({"local", "test", "loadtest"})
@RequiredArgsConstructor
public class WalletInboxInspectionController {

    private final WalletInboxInspectionService inspectionService;

    @GetMapping("/messages")
    public List<WalletInboxMessageView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String messageType,
            @RequestParam(defaultValue = "50") int limit) {
        return inspectionService.list(status, messageType, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidFilter(IllegalArgumentException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", failure.getMessage()));
    }
}

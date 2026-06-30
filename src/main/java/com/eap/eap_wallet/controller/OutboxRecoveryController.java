package com.eap.eap_wallet.controller;

import com.eap.eap_wallet.application.OutboxRecoveryService;
import com.eap.eap_wallet.domain.dto.OutboxRecoveryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/internal/outbox")
@ConditionalOnProperty(name = "eap.wallet.outbox-admin.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OutboxRecoveryController {

    private final OutboxRecoveryService outboxRecoveryService;

    @GetMapping("/failed")
    public List<OutboxRecoveryView> listFailed(@RequestParam(defaultValue = "50") int limit) {
        return outboxRecoveryService.listFailed(limit);
    }

    @PostMapping("/{id}/requeue")
    public ResponseEntity<?> requeue(@PathVariable long id) {
        try {
            OutboxRecoveryView result = outboxRecoveryService.requeueFailed(id);
            log.warn("FAILED outbox event manually requeued: id={}", id);
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}

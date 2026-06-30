package com.eap.eap_wallet.domain.dto;

import com.eap.eap_wallet.domain.entity.OutboxEntity;

import java.time.LocalDateTime;

public record OutboxRecoveryView(
        Long id,
        String eventType,
        String routingKey,
        String status,
        int attemptCount,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime nextRetryAt
) {
    public static OutboxRecoveryView from(OutboxEntity entry) {
        return new OutboxRecoveryView(
                entry.getId(),
                entry.getEventType(),
                entry.getRoutingKey(),
                entry.getStatus(),
                entry.getAttemptCount(),
                entry.getLastError(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getNextRetryAt()
        );
    }
}

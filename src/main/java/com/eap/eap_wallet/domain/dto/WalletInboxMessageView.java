package com.eap.eap_wallet.domain.dto;

import java.time.LocalDateTime;

public record WalletInboxMessageView(
        String messageType,
        String messageId,
        String status,
        int attemptCount,
        LocalDateTime nextRetryAt,
        String claimedBy,
        LocalDateTime claimUntil,
        LocalDateTime receivedAt,
        LocalDateTime appliedAt,
        String errorType,
        String lastError,
        LocalDateTime conflictDetectedAt,
        LocalDateTime updatedAt
) {
}

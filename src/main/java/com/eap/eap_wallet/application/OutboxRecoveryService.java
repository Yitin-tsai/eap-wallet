package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.domain.dto.OutboxRecoveryView;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class OutboxRecoveryService {

    private static final int MAX_LIST_LIMIT = 100;

    private final OutboxRepository outboxRepository;
    private final WalletMetrics walletMetrics;

    @Transactional(readOnly = true)
    public List<OutboxRecoveryView> listFailed(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIST_LIMIT);
        return outboxRepository.findByStatusOrderByUpdatedAtAsc("FAILED", PageRequest.of(0, limit))
                .stream()
                .map(OutboxRecoveryView::from)
                .toList();
    }

    @Transactional
    public OutboxRecoveryView requeueFailed(long id) {
        OutboxEntity entry = outboxRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Outbox event not found: " + id));
        if (!"FAILED".equals(entry.getStatus())) {
            throw new IllegalStateException(
                    "Only FAILED outbox events can be requeued: id=" + id + ", status=" + entry.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        entry.setStatus("PENDING");
        entry.setAttemptCount(0);
        entry.setNextRetryAt(now);
        entry.setLastError(null);
        entry.setUpdatedAt(now);
        OutboxEntity saved = outboxRepository.save(entry);
        walletMetrics.outboxRequeued();
        return OutboxRecoveryView.from(saved);
    }
}

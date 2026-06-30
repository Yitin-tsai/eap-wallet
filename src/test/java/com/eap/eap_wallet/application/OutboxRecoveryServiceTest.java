package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.domain.dto.OutboxRecoveryView;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRecoveryServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private WalletMetrics walletMetrics;

    private OutboxRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new OutboxRecoveryService(outboxRepository, walletMetrics);
    }

    @Test
    void failedEvent_shouldBeResetToPending() {
        OutboxEntity entry = failedEntry(10L);
        when(outboxRepository.findById(10L)).thenReturn(Optional.of(entry));
        when(outboxRepository.save(entry)).thenReturn(entry);

        OutboxRecoveryView result = recoveryService.requeueFailed(10L);

        assertEquals("PENDING", result.status());
        assertEquals(0, result.attemptCount());
        assertNull(result.lastError());
        assertNotNull(result.nextRetryAt());
        verify(outboxRepository).save(entry);
        verify(walletMetrics).outboxRequeued();
    }

    @Test
    void nonFailedEvent_shouldBeRejected() {
        OutboxEntity entry = failedEntry(11L);
        entry.setStatus("SENT");
        when(outboxRepository.findById(11L)).thenReturn(Optional.of(entry));

        assertThrows(IllegalStateException.class, () -> recoveryService.requeueFailed(11L));

        verify(outboxRepository, never()).save(entry);
        verify(walletMetrics, never()).outboxRequeued();
    }

    @Test
    void missingEvent_shouldReturnNotFoundError() {
        when(outboxRepository.findById(12L)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> recoveryService.requeueFailed(12L));
    }

    private OutboxEntity failedEntry(Long id) {
        OutboxEntity entry = new OutboxEntity("OrderConfirmedEvent", "order.confirmed", "{}");
        entry.setStatus("FAILED");
        entry.setAttemptCount(10);
        entry.setLastError("AmqpException: unroutable");
        entry.setNextRetryAt(null);
        entry.setUpdatedAt(LocalDateTime.now());
        try {
            var field = OutboxEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entry, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entry;
    }
}

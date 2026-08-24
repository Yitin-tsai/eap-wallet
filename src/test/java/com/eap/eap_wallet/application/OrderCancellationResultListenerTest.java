package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCancellationResultListenerTest {

    @Mock
    private WalletOrderCancellationAppender appender;
    @Mock
    private PlatformTransactionManager transactionManager;

    private OrderCancellationResultListener listener;

    @BeforeEach
    void setUp() {
        TransactionStatus transaction = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transaction);
        listener = new OrderCancellationResultListener(appender, transactionManager);
    }

    @Test
    void cancelledResult_shouldReleaseExactLockedAssets() {
        OrderCancellationResultEvent event = event(OrderCancellationResultEvent.CANCELLED);
        when(appender.release(event)).thenReturn(
                new WalletOrderCancellationAppender.CancellationOutcome(0, 0, 1, 1));

        listener.onResult(event);

        verify(appender).release(event);
    }

    @Test
    void rejectedResult_shouldNotTouchWallet() {
        OrderCancellationResultEvent event = event(OrderCancellationResultEvent.ALREADY_MATCHED);

        listener.onResult(event);

        verify(appender, never()).release(event);
    }

    @Test
    void notOpenResult_shouldNotTouchWallet() {
        OrderCancellationResultEvent event = event(OrderCancellationResultEvent.NOT_OPEN);

        listener.onResult(event);

        verify(appender, never()).release(event);
    }

    @Test
    void unknownResult_shouldFailClosed() {
        OrderCancellationResultEvent event = event("UNKNOWN");

        assertThatThrownBy(() -> listener.onResult(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown cancellation outcome");

        verify(appender, never()).release(event);
    }

    @Test
    void missingIdentity_shouldFailClosed() {
        OrderCancellationResultEvent event = event(OrderCancellationResultEvent.ALREADY_MATCHED);
        event.setCancellationId(null);

        assertThatThrownBy(() -> listener.onResult(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifiers and outcome are required");

        verify(appender, never()).release(event);
    }

    private OrderCancellationResultEvent event(String outcome) {
        return OrderCancellationResultEvent.builder()
                .cancellationId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .outcome(outcome)
                .orderType("BUY")
                .limitPrice(100)
                .cancelledAmount(5)
                .decidedAt(LocalDateTime.now())
                .build();
    }
}

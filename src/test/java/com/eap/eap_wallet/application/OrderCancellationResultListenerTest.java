package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCancellationResultListenerTest {

    @Mock WalletMessageInbox inbox;

    private OrderCancellationResultListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderCancellationResultListener(inbox);
    }

    @Test
    void result_shouldBePersistedBeforeListenerReturns() {
        OrderCancellationResultEvent event = event();
        when(inbox.receiveCancellationResult(event)).thenReturn(WalletMessageInbox.ReceiveOutcome.ACCEPTED);

        listener.onResult(event);

        verify(inbox).receiveCancellationResult(event);
    }

    @Test
    void inboxFailure_shouldEscapeSoBrokerDoesNotAck() {
        OrderCancellationResultEvent event = event();
        RuntimeException failure = new RuntimeException("database unavailable");
        doThrow(failure).when(inbox).receiveCancellationResult(event);

        assertThatThrownBy(() -> listener.onResult(event)).isSameAs(failure);
    }

    private OrderCancellationResultEvent event() {
        return OrderCancellationResultEvent.builder()
                .cancellationId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .outcome(OrderCancellationResultEvent.CANCELLED)
                .orderType("BUY")
                .limitPrice(100)
                .cancelledAmount(5)
                .build();
    }
}

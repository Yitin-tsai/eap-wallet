package com.eap.eap_wallet.application;

import com.eap.common.event.OrderSubmittedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
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
class CreateOrderListenerTest {

    @Mock WalletMessageInbox inbox;
    @Mock WalletMetrics metrics;

    private CreateOrderListener listener;

    @BeforeEach
    void setUp() {
        listener = new CreateOrderListener(inbox, metrics);
    }

    @Test
    void acceptedMessage_shouldReturnOnlyAfterDurableIntake() {
        OrderSubmittedEvent event = event();
        when(inbox.receiveOrderSubmitted(event)).thenReturn(WalletMessageInbox.ReceiveOutcome.ACCEPTED);

        listener.onOrderSubmitted(event);

        verify(inbox).receiveOrderSubmitted(event);
        verify(metrics).orderSubmittedConsumed();
    }

    @Test
    void duplicateMessage_shouldBeAcknowledgedWithoutBusinessReprocessing() {
        OrderSubmittedEvent event = event();
        when(inbox.receiveOrderSubmitted(event)).thenReturn(WalletMessageInbox.ReceiveOutcome.DUPLICATE);

        listener.onOrderSubmitted(event);

        verify(metrics).orderSubmittedDuplicateSkipped();
    }

    @Test
    void inboxFailure_shouldEscapeSoBrokerDoesNotAck() {
        OrderSubmittedEvent event = event();
        RuntimeException failure = new RuntimeException("database unavailable");
        doThrow(failure).when(inbox).receiveOrderSubmitted(event);

        assertThatThrownBy(() -> listener.onOrderSubmitted(event)).isSameAs(failure);
    }

    private OrderSubmittedEvent event() {
        return OrderSubmittedEvent.builder()
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .price(100)
                .amount(10)
                .orderType("BUY")
                .build();
    }
}

package com.eap.eap_wallet.application;

import com.eap.common.event.OrderSubmittedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.eap.common.constants.RabbitMQConstants.WALLET_ORDER_SUBMITTED_QUEUE;
import static com.eap.eap_wallet.configuration.reliability.WalletCdaDatabaseOutageCircuitBreaker.ORDER_SUBMITTED_LISTENER_ID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreateOrderListener {

    private final WalletMessageInbox inbox;
    private final WalletMetrics walletMetrics;

    /**
     * Durable intake boundary. Returning lets the container ACK only after the
     * message is stored or verified as an identical duplicate.
     */
    @RabbitListener(
            id = ORDER_SUBMITTED_LISTENER_ID,
            queues = WALLET_ORDER_SUBMITTED_QUEUE,
            concurrency = "${eap.wallet.listeners.order-submitted.concurrency:8}")
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        long startedAt = System.nanoTime();
        walletMetrics.orderSubmittedConsumed();
        try {
            WalletMessageInbox.ReceiveOutcome outcome = inbox.receiveOrderSubmitted(event);
            if (outcome == WalletMessageInbox.ReceiveOutcome.DUPLICATE) {
                walletMetrics.orderSubmittedDuplicateSkipped();
            } else if (outcome == WalletMessageInbox.ReceiveOutcome.CONFLICT) {
                log.error("Durable Wallet inbox identity conflict: orderId={}", event.getOrderId());
            }
        } finally {
            walletMetrics.recordOrderSubmittedProcessing(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }
}

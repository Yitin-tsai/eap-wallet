package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.eap.common.constants.RabbitMQConstants.WALLET_ORDER_CANCELLATION_RESULT_QUEUE;
import static com.eap.eap_wallet.configuration.reliability.WalletCdaDatabaseOutageCircuitBreaker.CANCELLATION_RESULT_LISTENER_ID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationResultListener {

    private final WalletMessageInbox inbox;

    @RabbitListener(
            id = CANCELLATION_RESULT_LISTENER_ID,
            queues = WALLET_ORDER_CANCELLATION_RESULT_QUEUE,
            concurrency = "${eap.wallet.listeners.order-cancellation-result.concurrency:4}")
    public void onResult(OrderCancellationResultEvent event) {
        WalletMessageInbox.ReceiveOutcome outcome = inbox.receiveCancellationResult(event);
        if (outcome == WalletMessageInbox.ReceiveOutcome.CONFLICT) {
            log.error("Durable Wallet inbox cancellation identity conflict: cancellationId={}",
                    event.getCancellationId());
        }
    }
}

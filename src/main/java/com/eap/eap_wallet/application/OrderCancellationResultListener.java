package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.eap.common.constants.RabbitMQConstants.WALLET_ORDER_CANCELLATION_RESULT_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationResultListener {

    private final WalletOrderCancellationAppender appender;
    private final PlatformTransactionManager transactionManager;

    @RabbitListener(
            queues = WALLET_ORDER_CANCELLATION_RESULT_QUEUE,
            concurrency = "${eap.wallet.listeners.order-cancellation-result.concurrency:4}")
    public void onResult(OrderCancellationResultEvent event) {
        validateEnvelope(event);
        if (!event.cancelled()) {
            if (OrderCancellationResultEvent.ALREADY_MATCHED.equals(event.getOutcome())
                    || OrderCancellationResultEvent.NOT_OPEN.equals(event.getOutcome())) {
                return;
            }
            throw new IllegalArgumentException("Unknown cancellation outcome: " + event.getOutcome());
        }
        WalletOrderCancellationAppender.CancellationOutcome outcome =
                new TransactionTemplate(transactionManager).execute(status -> appender.release(event));
        if (outcome == null) {
            throw new IllegalStateException("Wallet cancellation transaction returned no outcome");
        }
        if (outcome.duplicate()) {
            log.debug("Skipping duplicate wallet cancellation release: cancellationId={}",
                    event.getCancellationId());
            return;
        }
        if (!outcome.completed()) {
            throw new IllegalStateException("Wallet cancellation release did not converge: cancellationId="
                    + event.getCancellationId() + ", outcome=" + outcome);
        }
        log.info("Released cancelled order locked assets: cancellationId={}, orderId={}, amount={}",
                event.getCancellationId(), event.getOrderId(), event.getCancelledAmount());
    }

    private void validateEnvelope(OrderCancellationResultEvent event) {
        if (event == null || event.getCancellationId() == null
                || event.getOrderId() == null || event.getUserId() == null
                || event.getOutcome() == null) {
            throw new IllegalArgumentException("Cancellation result identifiers and outcome are required");
        }
    }
}

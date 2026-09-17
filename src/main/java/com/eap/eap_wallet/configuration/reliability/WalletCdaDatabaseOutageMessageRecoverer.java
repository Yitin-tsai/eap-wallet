package com.eap.eap_wallet.configuration.reliability;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageBatchRecoverer;

import java.util.List;

import static com.eap.common.reliability.TransientDatabaseOutageClassifier.isDatabaseUnavailable;

public class WalletCdaDatabaseOutageMessageRecoverer implements MessageBatchRecoverer {

    private final WalletCdaDatabaseOutageCircuitBreaker circuitBreaker;

    public WalletCdaDatabaseOutageMessageRecoverer(WalletCdaDatabaseOutageCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        recover(List.of(message), cause);
    }

    @Override
    public void recover(List<Message> messages, Throwable cause) {
        String sourceQueue = messages == null || messages.isEmpty()
                ? null
                : messages.get(0).getMessageProperties().getConsumerQueue();
        if (circuitBreaker.ownsQueue(sourceQueue) && isDatabaseUnavailable(cause)) {
            circuitBreaker.open(cause);
            throw new ImmediateRequeueAmqpException(
                    "Wallet database unavailable before durable consumer commit", cause);
        }
        throw new AmqpRejectAndDontRequeueException(
                "Wallet listener retries exhausted for a non-outage failure", true, cause);
    }
}

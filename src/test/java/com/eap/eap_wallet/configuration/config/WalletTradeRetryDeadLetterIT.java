package com.eap.eap_wallet.configuration.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfSystemProperty(named = "eap.integration.rabbit", matches = "true")
class WalletTradeRetryDeadLetterIT {

    @Test
    void retryExhaustion_rejectsMessageIntoConfiguredDeadLetterQueue() {
        String suffix = UUID.randomUUID().toString();
        String sourceQueue = "wallet.trade.retry.it." + suffix;
        String deadLetterExchange = "wallet.trade.retry.it.dlx." + suffix;
        String deadLetterQueue = "wallet.trade.retry.it.dlq." + suffix;

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                System.getProperty("eap.integration.rabbit.host", "localhost"),
                Integer.getInteger("eap.integration.rabbit.port", 5672));
        connectionFactory.setUsername(System.getProperty("eap.integration.rabbit.user", "admin"));
        connectionFactory.setPassword(System.getProperty("eap.integration.rabbit.password", "admin123"));
        connectionFactory.setVirtualHost(System.getProperty("eap.integration.rabbit.vhost", "/"));

        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        FanoutExchange dlx = new FanoutExchange(deadLetterExchange);
        Queue dlq = QueueBuilder.nonDurable(deadLetterQueue).build();
        Queue source = QueueBuilder.nonDurable(sourceQueue)
                .withArgument("x-dead-letter-exchange", deadLetterExchange)
                .build();
        rabbitAdmin.declareExchange(dlx);
        rabbitAdmin.declareQueue(dlq);
        rabbitAdmin.declareBinding(BindingBuilder.bind(dlq).to(dlx));
        rabbitAdmin.declareQueue(source);

        AtomicInteger attempts = new AtomicInteger();
        SimpleMessageListenerContainer listener = new SimpleMessageListenerContainer(connectionFactory);
        listener.setQueueNames(sourceQueue);
        listener.setDefaultRequeueRejected(false);
        listener.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(2)
                .backOffOptions(1, 1, 1)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        listener.setMessageListener((MessageListener) message -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("poison settlement");
        });

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        try {
            listener.start();
            rabbitTemplate.convertAndSend("", sourceQueue, "trade-1");

            Message deadLetter = rabbitTemplate.receive(deadLetterQueue, Duration.ofSeconds(10).toMillis());

            assertNotNull(deadLetter);
            assertEquals("trade-1", new String(deadLetter.getBody(), StandardCharsets.UTF_8));
            assertEquals(2, attempts.get());
        } finally {
            listener.stop();
            rabbitAdmin.deleteQueue(sourceQueue);
            rabbitAdmin.deleteQueue(deadLetterQueue);
            rabbitAdmin.deleteExchange(deadLetterExchange);
            connectionFactory.destroy();
        }
    }
}

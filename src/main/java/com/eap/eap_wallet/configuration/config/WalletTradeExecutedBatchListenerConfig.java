package com.eap.eap_wallet.configuration.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WalletTradeExecutedBatchListenerConfig {

    @Bean
    public SimpleRabbitListenerContainerFactory walletTradeExecutedBatchListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${eap.wallet.listeners.trade-executed.batch-size:1}") int batchSize,
            @Value("${eap.wallet.listeners.trade-executed.receive-timeout-ms:50}") long receiveTimeoutMs) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(Math.max(1, batchSize));
        factory.setReceiveTimeout(receiveTimeoutMs);
        return factory;
    }
}

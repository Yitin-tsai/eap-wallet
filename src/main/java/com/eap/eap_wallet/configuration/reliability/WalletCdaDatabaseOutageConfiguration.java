package com.eap.eap_wallet.configuration.reliability;

import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WalletCdaDatabaseOutageConfiguration {

    @Bean
    public MessageRecoverer walletCdaDatabaseOutageMessageRecoverer(
            WalletCdaDatabaseOutageCircuitBreaker circuitBreaker) {
        return new WalletCdaDatabaseOutageMessageRecoverer(circuitBreaker);
    }

    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> walletForceStopContainerCustomizer() {
        return container -> container.setForceStop(true);
    }
}

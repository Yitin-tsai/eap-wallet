package com.eap.eap_wallet.configuration.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static com.eap.common.constants.RabbitMQConstants.*;

/**
 * Wallet Module RabbitMQ Configuration
 * 
 * This module consumes:
 * - order.create events (for wallet validation)
 * - order.matched events (for balance updates)
 * 
 * Topology: Each module gets its own queues bound to shared routing keys
 */
@Configuration
public class RabbitMQConfig {

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  public TopicExchange orderExchange() {
    return new TopicExchange(ORDER_EXCHANGE);
  }

  // Wallet-specific queue for order submission validation
  @Bean
  public Queue walletOrderSubmittedQueue() {
    return QueueBuilder.durable(WALLET_ORDER_SUBMITTED_QUEUE)
        .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
        .build();
  }

  // Wallet-specific queue for order matched events (balance settlement)
  @Bean
  public Queue walletOrderMatchedQueue() {
    return QueueBuilder.durable(WALLET_ORDER_MATCHED_QUEUE)
        .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
        .build();
  }

  @Bean
  public Binding walletOrderSubmittedBinding(@Qualifier("walletOrderSubmittedQueue") Queue walletOrderSubmittedQueue,
      TopicExchange orderExchange) {
    return BindingBuilder.bind(walletOrderSubmittedQueue).to(orderExchange).with(ORDER_SUBMITTED_KEY);
  }

  @Bean
  public Binding walletOrderMatchedBinding(@Qualifier("walletOrderMatchedQueue") Queue walletOrderMatchedQueue, 
      TopicExchange orderExchange) {
    return BindingBuilder.bind(walletOrderMatchedQueue).to(orderExchange).with(ORDER_MATCHED_KEY);
  }
}

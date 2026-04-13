package com.eap.eap_wallet.configuration.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
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
 * - auction.bid.submitted events (for auction fund locking)
 * - auction.cleared events (for auction settlement)
 *
 * Topology: Each module gets its own queues bound to shared routing keys
 */
@Configuration
public class RabbitMQConfig {

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  // --- Dead Letter Exchange / Queue (shared, idempotent declare) ---

  @Bean
  public FanoutExchange deadLetterExchange() {
    return new FanoutExchange(DEAD_LETTER_EXCHANGE);
  }

  @Bean
  public Queue deadLetterQueue() {
    return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
  }

  @Bean
  public Binding dlqBinding(@Qualifier("deadLetterQueue") Queue deadLetterQueue,
                            FanoutExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
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

  // === Auction Exchange & Queues ===

  @Bean
  public TopicExchange auctionExchange() {
    return new TopicExchange(AUCTION_EXCHANGE);
  }

  // Wallet-specific queue for auction bid submitted events (fund locking)
  @Bean
  public Queue walletAuctionBidSubmittedQueue() {
    return QueueBuilder.durable(WALLET_AUCTION_BID_SUBMITTED_QUEUE)
        .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
        .build();
  }

  // Wallet-specific queue for auction cleared events (settlement)
  @Bean
  public Queue walletAuctionClearedQueue() {
    return QueueBuilder.durable(WALLET_AUCTION_CLEARED_QUEUE)
        .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
        .build();
  }

  @Bean
  public Binding walletAuctionBidSubmittedBinding(
      @Qualifier("walletAuctionBidSubmittedQueue") Queue walletAuctionBidSubmittedQueue,
      @Qualifier("auctionExchange") TopicExchange auctionExchange) {
    return BindingBuilder.bind(walletAuctionBidSubmittedQueue).to(auctionExchange).with(AUCTION_BID_SUBMITTED_KEY);
  }

  @Bean
  public Binding walletAuctionClearedBinding(
      @Qualifier("walletAuctionClearedQueue") Queue walletAuctionClearedQueue,
      @Qualifier("auctionExchange") TopicExchange auctionExchange) {
    return BindingBuilder.bind(walletAuctionClearedQueue).to(auctionExchange).with(AUCTION_CLEARED_KEY);
  }
}

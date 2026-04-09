package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderSubmittedEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = {CreateOrderListener.class, BaseContractTest.TestConfiguration.class})
@AutoConfigureMessageVerifier
public class BaseContractTest {

  @Configuration
  static class TestConfiguration {
    @Bean("order.exchange")
    public PollableChannel orderExchange() {
      return new QueueChannel();
    }

    @Bean
    @Primary
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
      mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      return mapper;
    }
  }

  @Autowired private CreateOrderListener createOrderListener;

  @MockitoBean private WalletRepository walletRepository;

  @MockitoBean private RabbitTemplate rabbitTemplate;

  @Autowired private PollableChannel orderExchange;

  @BeforeEach
  void setup() {
    
    // 根據你的 CreateOrderListener，確保 mock 正確設定
    UUID testUserId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    WalletEntity wallet =
        WalletEntity.builder()
            .userId(testUserId)
            .availableCurrency(10000) // 確保餘額足夠（100 * 1 = 100 < 10000）
            .lockedCurrency(0) // 添加 lockedCurrency 初始值
            .availableAmount(1000) // 添加 availableAmount 初始值
            .lockedAmount(0) // 添加 lockedAmount 初始值
            .build();

    // 如果 findByUserId 參數是 UUID，使用 UUID；如果是 String，使用 String
    Mockito.when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);

    // 捕獲 RabbitTemplate 的發送操作並轉發到測試 channel
    Mockito.doAnswer(
            invocation -> {
              String exchange = invocation.getArgument(0);
              String routingKey = invocation.getArgument(1);
              Object message = invocation.getArgument(2);

              if ("order.exchange".equals(exchange)) {
                org.springframework.messaging.Message<?> msg =
                    org.springframework.messaging.support.MessageBuilder.withPayload(message)
                        .setHeader("rabbitmq_routingKey", routingKey)
                        .build();
                orderExchange.send(msg);
              }
              return null;
            })
        .when(rabbitTemplate)
        .convertAndSend(
            Mockito.any(String.class), Mockito.any(String.class), Mockito.any(Object.class));
  }

  public void processOrderCreate() { // 改名符合 contract 中的 triggeredBy
    // 讓 Spring Cloud Contract 產生的測試能夠呼叫此方法
    createOrderListener.onOrderSubmitted(
        OrderSubmittedEvent.builder()
            .orderId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
            .userId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")) // 保持 UUID
            .price(100)
            .amount(1)
            .orderType("BUY")
            .createdAt(LocalDateTime.parse("2025-07-16T12:00:00"))
            .build());
  }
}

package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderSubmittedEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateOrderListenerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private CreateOrderListener createOrderListener;

    private UUID testUserId;
    private UUID testOrderId;
    private LocalDateTime testCreatedAt;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testOrderId = UUID.randomUUID();
        testCreatedAt = LocalDateTime.now();
    }

    @Test
    void testOnOrderCreate_WhenWalletHasSufficientBalance_ShouldPublishOrderConfirmedEvent() {
        // Given
        OrderSubmittedEvent orderCreateEvent = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(50)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        WalletEntity walletEntity = WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableAmount(100)
                .availableCurrency(1000000000)
                .lockedCurrency(0)
                .lockedAmount(0)
                .updateTime(LocalDateTime.now())
                .build();

        when(walletRepository.findByUserId(testUserId)).thenReturn(walletEntity);

        // When
        createOrderListener.onOrderSubmitted(orderCreateEvent);

        // Then
        ArgumentCaptor<OrderConfirmedEvent> eventCaptor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.confirmed"), eventCaptor.capture());

        OrderConfirmedEvent capturedEvent = eventCaptor.getValue();
        assertEquals(testOrderId, capturedEvent.getOrderId());
        assertEquals(testUserId, capturedEvent.getUserId());
        assertEquals(1000, capturedEvent.getPrice());
        assertEquals(50, capturedEvent.getAmount());
        assertEquals("BUY", capturedEvent.getOrderType());
        assertEquals(testCreatedAt, capturedEvent.getCreatedAt());
    }

    @Test
    void testOnOrderCreate_WhenWalletHasInsufficientBalance_ShouldSendOrderFailedEvent() {
        // Given
        OrderSubmittedEvent orderCreateEvent = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(150) // 超過可用餘額 (150 * 1000 = 150,000 > 1)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        WalletEntity walletEntity = WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableAmount(100)
                .availableCurrency(1) // 可用貨幣很少，不足以購買
                .lockedAmount(0)
                .lockedCurrency(0)
                .updateTime(LocalDateTime.now())
                .build();

        when(walletRepository.findByUserId(testUserId)).thenReturn(walletEntity);

        // When
        createOrderListener.onOrderSubmitted(orderCreateEvent);

        // Then
        // 驗證發送了 OrderFailedEvent
        ArgumentCaptor<OrderFailedEvent> failedEventCaptor = ArgumentCaptor.forClass(OrderFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.failed"), failedEventCaptor.capture());

        OrderFailedEvent capturedFailedEvent = failedEventCaptor.getValue();
        assertEquals(testOrderId, capturedFailedEvent.getOrderId());
        assertEquals(testUserId, capturedFailedEvent.getUserId());
        assertEquals("餘額不足", capturedFailedEvent.getReason());
        assertEquals("INSUFFICIENT_BALANCE", capturedFailedEvent.getFailureType());
        assertNotNull(capturedFailedEvent.getFailedAt());

        // 驗證沒有發送 OrderConfirmedEvent
        verify(rabbitTemplate, never()).convertAndSend(anyString(), eq("order.confirmed"), any(OrderConfirmedEvent.class));
        
        // 驗證沒有保存錢包（因為沒有鎖定資產）
        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

    @Test
    void testOnOrderCreate_WhenWalletHasInsufficientAmountForSell_ShouldSendOrderFailedEvent() {
        // Given
        OrderSubmittedEvent orderCreateEvent = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(150) // 超過可用電量
                .orderType("SELL")
                .createdAt(testCreatedAt)
                .build();

        WalletEntity walletEntity = WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableAmount(100) // 可用電量不足
                .availableCurrency(1000000)
                .lockedAmount(0)
                .lockedCurrency(0)
                .updateTime(LocalDateTime.now())
                .build();

        when(walletRepository.findByUserId(testUserId)).thenReturn(walletEntity);

        // When
        createOrderListener.onOrderSubmitted(orderCreateEvent);

        // Then
        // 驗證發送了 OrderFailedEvent
        ArgumentCaptor<OrderFailedEvent> failedEventCaptor = ArgumentCaptor.forClass(OrderFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq("order.exchange"), eq("order.failed"), failedEventCaptor.capture());

        OrderFailedEvent capturedFailedEvent = failedEventCaptor.getValue();
        assertEquals(testOrderId, capturedFailedEvent.getOrderId());
        assertEquals(testUserId, capturedFailedEvent.getUserId());
        assertEquals("可用電量不足", capturedFailedEvent.getReason());
        assertEquals("INSUFFICIENT_AMOUNT", capturedFailedEvent.getFailureType());
        assertNotNull(capturedFailedEvent.getFailedAt());

        // 驗證沒有發送 OrderConfirmedEvent
        verify(rabbitTemplate, never()).convertAndSend(anyString(), eq("order.confirmed"), any(OrderConfirmedEvent.class));
        
        // 驗證沒有保存錢包（因為沒有鎖定資產）
        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

  
}

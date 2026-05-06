package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderSubmittedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
    private OutboxRepository outboxRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;

    private CreateOrderListener createOrderListener;

    private UUID testUserId;
    private UUID testOrderId;
    private LocalDateTime testCreatedAt;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testOrderId = UUID.randomUUID();
        testCreatedAt = LocalDateTime.now();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Make TransactionTemplate execute synchronously
        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);

        createOrderListener = new CreateOrderListener();
        // Inject dependencies via reflection
        setField(createOrderListener, "walletRepository", walletRepository);
        setField(createOrderListener, "outboxRepository", outboxRepository);
        setField(createOrderListener, "objectMapper", objectMapper);
        setField(createOrderListener, "transactionManager", transactionManager);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testOnOrderCreate_WhenWalletHasSufficientBalance_ShouldWriteOutboxConfirmedEvent() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
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

        createOrderListener.onOrderSubmitted(event);

        verify(walletRepository).save(walletEntity);
        assertEquals(1000000000 - 50000, walletEntity.getAvailableCurrency());
        assertEquals(50000, walletEntity.getLockedCurrency());

        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals("OrderConfirmedEvent", outboxCaptor.getValue().getEventType());
    }

    @Test
    void testOnOrderCreate_WhenWalletHasInsufficientBalance_ShouldWriteOutboxFailedEvent() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(150)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        WalletEntity walletEntity = WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableAmount(100)
                .availableCurrency(1)
                .lockedAmount(0)
                .lockedCurrency(0)
                .updateTime(LocalDateTime.now())
                .build();

        when(walletRepository.findByUserId(testUserId)).thenReturn(walletEntity);

        createOrderListener.onOrderSubmitted(event);

        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals("OrderFailedEvent", outboxCaptor.getValue().getEventType());

        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

    @Test
    void testOnOrderCreate_WhenWalletHasInsufficientAmountForSell_ShouldWriteOutboxFailedEvent() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(150)
                .orderType("SELL")
                .createdAt(testCreatedAt)
                .build();

        WalletEntity walletEntity = WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableAmount(100)
                .availableCurrency(1000000)
                .lockedAmount(0)
                .lockedCurrency(0)
                .updateTime(LocalDateTime.now())
                .build();

        when(walletRepository.findByUserId(testUserId)).thenReturn(walletEntity);

        createOrderListener.onOrderSubmitted(event);

        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals("OrderFailedEvent", outboxCaptor.getValue().getEventType());

        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

    @Test
    void optimisticLock_firstAttemptFails_secondSucceeds_shouldRetryAndProcess() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(100)
                .amount(10)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        WalletEntity wallet = WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableAmount(100)
                .availableCurrency(10000)
                .lockedCurrency(0)
                .lockedAmount(0)
                .updateTime(LocalDateTime.now())
                .build();

        when(walletRepository.findByUserId(testUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenReturn(wallet);

        createOrderListener.onOrderSubmitted(event);

        verify(walletRepository, times(2)).findByUserId(testUserId);
        verify(walletRepository).save(wallet);
    }

    @Test
    void optimisticLock_allThreeAttemptsFail_shouldThrowAfterMaxRetries() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(100)
                .amount(10)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        when(walletRepository.findByUserId(testUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L));

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> createOrderListener.onOrderSubmitted(event));

        verify(walletRepository, times(3)).findByUserId(testUserId);
    }
}

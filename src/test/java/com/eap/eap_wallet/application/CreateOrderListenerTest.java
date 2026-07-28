package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.OrderSubmissionIdempotencyRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.OrderSubmittedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateOrderListenerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private OrderSubmissionIdempotencyRepository orderSubmissionIdempotencyRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private WalletMetrics walletMetrics;

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
        setField(createOrderListener, "jdbcTemplate", jdbcTemplate);
        setField(createOrderListener, "orderSubmissionIdempotencyRepository", orderSubmissionIdempotencyRepository);
        setField(createOrderListener, "objectMapper", objectMapper);
        setField(createOrderListener, "transactionManager", transactionManager);
        setField(createOrderListener, "walletMetrics", walletMetrics);
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

        when(orderSubmissionIdempotencyRepository.claimOrderSubmission(testOrderId, testUserId)).thenReturn(1);
        when(walletRepository.reserveCurrencyForBuy(testUserId, 50000)).thenReturn(1);

        createOrderListener.onOrderSubmitted(event);

        verify(walletRepository).reserveCurrencyForBuy(testUserId, 50000);
        verify(walletRepository, never()).findByUserId(testUserId);
        verify(walletRepository, never()).save(any(WalletEntity.class));

        verify(jdbcTemplate).update(
                contains("INSERT INTO wallet_service.outbox"),
                eq("OrderConfirmedEvent"),
                anyString(),
                anyString());
    }

    @Test
    void duplicateOrderSubmittedEvent_shouldLockAssetOnlyOnce() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(10)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        when(orderSubmissionIdempotencyRepository.claimOrderSubmission(testOrderId, testUserId))
                .thenReturn(1)
                .thenReturn(0);
        when(walletRepository.reserveCurrencyForBuy(testUserId, 10000)).thenReturn(1);

        createOrderListener.onOrderSubmitted(event);
        createOrderListener.onOrderSubmitted(event);

        verify(walletRepository, never()).findByUserId(testUserId);
        verify(walletRepository, times(1)).reserveCurrencyForBuy(testUserId, 10000);
        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(jdbcTemplate, times(1)).update(
                contains("INSERT INTO wallet_service.outbox"),
                eq("OrderConfirmedEvent"),
                anyString(),
                anyString());
        verify(orderSubmissionIdempotencyRepository, times(2))
                .claimOrderSubmission(testOrderId, testUserId);

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

        when(orderSubmissionIdempotencyRepository.claimOrderSubmission(testOrderId, testUserId)).thenReturn(1);
        when(walletRepository.reserveCurrencyForBuy(testUserId, 150000)).thenReturn(0);
        when(walletRepository.existsByUserId(testUserId)).thenReturn(true);

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate).update(
                contains("INSERT INTO wallet_service.outbox"),
                eq("OrderFailedEvent"),
                anyString(),
                anyString());

        verify(walletRepository).reserveCurrencyForBuy(testUserId, 150000);
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

        when(orderSubmissionIdempotencyRepository.claimOrderSubmission(testOrderId, testUserId)).thenReturn(1);
        when(walletRepository.reserveAmountForSell(testUserId, 150)).thenReturn(0);
        when(walletRepository.existsByUserId(testUserId)).thenReturn(true);

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate).update(
                contains("INSERT INTO wallet_service.outbox"),
                eq("OrderFailedEvent"),
                anyString(),
                anyString());

        verify(walletRepository).reserveAmountForSell(testUserId, 150);
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

        when(orderSubmissionIdempotencyRepository.claimOrderSubmission(testOrderId, testUserId)).thenReturn(1);
        when(walletRepository.reserveCurrencyForBuy(testUserId, 1000))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenReturn(1);

        createOrderListener.onOrderSubmitted(event);

        verify(orderSubmissionIdempotencyRepository, times(2)).claimOrderSubmission(testOrderId, testUserId);
        verify(walletRepository, times(2)).reserveCurrencyForBuy(testUserId, 1000);
        verify(walletRepository, never()).save(any(WalletEntity.class));
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

        when(orderSubmissionIdempotencyRepository.claimOrderSubmission(testOrderId, testUserId)).thenReturn(1);
        when(walletRepository.reserveCurrencyForBuy(testUserId, 1000))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L));

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> createOrderListener.onOrderSubmitted(event));

        verify(orderSubmissionIdempotencyRepository, times(3)).claimOrderSubmission(testOrderId, testUserId);
        verify(walletRepository, times(3)).reserveCurrencyForBuy(testUserId, 1000);
    }
}

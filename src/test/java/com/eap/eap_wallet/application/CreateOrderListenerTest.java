package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.common.event.OrderSubmittedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateOrderListenerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

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
        setField(createOrderListener, "jdbcTemplate", jdbcTemplate);
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

        mockReservationOutcome(new CreateOrderListener.ReservationOutcome(1, 1, 1, 1));

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO wallet_service.outbox"), any(Object[].class));
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

        mockReservationOutcomes(
                new CreateOrderListener.ReservationOutcome(1, 1, 1, 1),
                new CreateOrderListener.ReservationOutcome(0, 0, 0, 0));

        createOrderListener.onOrderSubmitted(event);
        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate, times(2)).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
        verify(walletMetrics).orderSubmittedDuplicateSkipped();
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

        mockReservationOutcome(new CreateOrderListener.ReservationOutcome(1, 1, 0, 1));

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
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

        mockReservationOutcome(new CreateOrderListener.ReservationOutcome(1, 1, 0, 1));

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
    }

    @Test
    void invalidOrderType_shouldUseIdempotentReservationCte() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(1000)
                .amount(150)
                .orderType("INVALID")
                .createdAt(testCreatedAt)
                .build();

        mockReservationOutcome(new CreateOrderListener.ReservationOutcome(1, 1, 0, 1));

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO wallet_service.outbox"), any(Object[].class));
    }

    @Test
    void reservationConflict_firstAttemptFails_secondSucceeds_shouldRetryAndProcess() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(100)
                .amount(10)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        doThrow(new CannotAcquireLockException("conflict"))
                .doReturn(new CreateOrderListener.ReservationOutcome(1, 1, 1, 1))
                .when(jdbcTemplate)
                .queryForObject(contains("WITH claimed"), any(RowMapper.class), any(Object[].class));

        createOrderListener.onOrderSubmitted(event);

        verify(jdbcTemplate, times(2)).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
        verify(walletMetrics).optimisticLockRetry();
    }

    @Test
    void reservationConflict_allThreeAttemptsFail_shouldThrowAfterMaxRetries() {
        OrderSubmittedEvent event = OrderSubmittedEvent.builder()
                .orderId(testOrderId)
                .userId(testUserId)
                .price(100)
                .amount(10)
                .orderType("BUY")
                .createdAt(testCreatedAt)
                .build();

        doThrow(new CannotAcquireLockException("conflict"))
                .when(jdbcTemplate)
                .queryForObject(contains("WITH claimed"), any(RowMapper.class), any(Object[].class));

        assertThrows(CannotAcquireLockException.class,
                () -> createOrderListener.onOrderSubmitted(event));

        verify(jdbcTemplate, times(3)).queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
    }

    private void mockReservationOutcome(CreateOrderListener.ReservationOutcome outcome) {
        mockReservationOutcomes(outcome);
    }

    private void mockReservationOutcomes(CreateOrderListener.ReservationOutcome first,
                                         CreateOrderListener.ReservationOutcome... rest) {
        List<CreateOrderListener.ReservationOutcome> outcomes = new ArrayList<>();
        outcomes.add(first);
        outcomes.addAll(List.of(rest));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> outcomes.get(Math.min(calls.getAndIncrement(), outcomes.size() - 1)))
                .when(jdbcTemplate)
                .queryForObject(
                contains("WITH claimed"),
                any(RowMapper.class),
                any(Object[].class));
    }
}

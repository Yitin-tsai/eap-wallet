package com.eap.eap_wallet.application;

import com.eap.common.event.AuctionBidSubmittedEvent;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuctionBidListenerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private AuctionBidListener auctionBidListener;

    private UUID testUserId;
    private String testAuctionId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testAuctionId = "auction-001";

        // Make TransactionTemplate execute the callback directly
        TransactionStatus mockStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any(DefaultTransactionDefinition.class)))
                .thenReturn(mockStatus);
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private WalletEntity buildWallet(int availableCurrency, int lockedCurrency,
                                     int availableAmount, int lockedAmount) {
        return WalletEntity.builder()
                .id(1L)
                .userId(testUserId)
                .availableCurrency(availableCurrency)
                .lockedCurrency(lockedCurrency)
                .availableAmount(availableAmount)
                .lockedAmount(lockedAmount)
                .updateTime(LocalDateTime.now())
                .build();
    }

    private AuctionBidSubmittedEvent buildEvent(String side, int totalLocked) {
        return AuctionBidSubmittedEvent.builder()
                .auctionId(testAuctionId)
                .userId(testUserId)
                .side(side)
                .totalLocked(totalLocked)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // -------------------------------------------------------------------------
    // BUY: lock currency + write outbox
    // -------------------------------------------------------------------------

    @Test
    void buy_sufficientCurrency_shouldLockCurrencyAndWriteOutbox() {
        WalletEntity wallet = buildWallet(10000, 0, 100, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 3000);

        auctionBidListener.onAuctionBidSubmitted(event);

        // Verify wallet lock
        ArgumentCaptor<WalletEntity> walletCaptor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(walletCaptor.capture());
        WalletEntity saved = walletCaptor.getValue();
        assertEquals(7000, saved.getAvailableCurrency());
        assertEquals(3000, saved.getLockedCurrency());
        assertEquals(100, saved.getAvailableAmount());
        assertEquals(0, saved.getLockedAmount());

        // Verify outbox entry
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEntity outbox = outboxCaptor.getValue();
        assertEquals("AuctionBidConfirmedEvent", outbox.getEventType());
        assertEquals("auction.bid.confirmed", outbox.getRoutingKey());
    }

    @Test
    void buy_insufficientCurrency_shouldNotSaveAndNotWriteOutbox() {
        WalletEntity wallet = buildWallet(1000, 0, 100, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 5000);

        // Should not throw — just logs warning and returns
        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(outboxRepository, never()).save(any(OutboxEntity.class));
        assertEquals(1000, wallet.getAvailableCurrency());
        assertEquals(0, wallet.getLockedCurrency());
    }

    // -------------------------------------------------------------------------
    // SELL: lock amount (energy) + write outbox
    // -------------------------------------------------------------------------

    @Test
    void sell_sufficientAmount_shouldLockAmountAndWriteOutbox() {
        WalletEntity wallet = buildWallet(0, 0, 500, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("SELL", 200);

        auctionBidListener.onAuctionBidSubmitted(event);

        ArgumentCaptor<WalletEntity> walletCaptor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(walletCaptor.capture());
        WalletEntity saved = walletCaptor.getValue();
        assertEquals(300, saved.getAvailableAmount());
        assertEquals(200, saved.getLockedAmount());
        assertEquals(0, saved.getAvailableCurrency());
        assertEquals(0, saved.getLockedCurrency());

        verify(outboxRepository).save(any(OutboxEntity.class));
    }

    @Test
    void sell_insufficientAmount_shouldNotSaveAndNotWriteOutbox() {
        WalletEntity wallet = buildWallet(0, 0, 100, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("SELL", 200);

        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(outboxRepository, never()).save(any(OutboxEntity.class));
        assertEquals(100, wallet.getAvailableAmount());
        assertEquals(0, wallet.getLockedAmount());
    }

    // -------------------------------------------------------------------------
    // Wallet not found
    // -------------------------------------------------------------------------

    @Test
    void walletNotFound_shouldNotThrowAndNotSaveAndNotWriteOutbox() {
        when(walletRepository.findByUserId(testUserId)).thenReturn(null);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 1000);

        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        verify(walletRepository, never()).save(any(WalletEntity.class));
        verify(outboxRepository, never()).save(any(OutboxEntity.class));
    }

    // -------------------------------------------------------------------------
    // Exact boundary: available == totalLocked (should succeed)
    // -------------------------------------------------------------------------

    @Test
    void buy_exactBalance_shouldLockCurrencyAndWriteOutbox() {
        WalletEntity wallet = buildWallet(3000, 0, 0, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 3000);

        auctionBidListener.onAuctionBidSubmitted(event);

        ArgumentCaptor<WalletEntity> walletCaptor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(walletCaptor.capture());
        WalletEntity saved = walletCaptor.getValue();
        assertEquals(0, saved.getAvailableCurrency());
        assertEquals(3000, saved.getLockedCurrency());

        verify(outboxRepository).save(any(OutboxEntity.class));
    }

    @Test
    void sell_exactBalance_shouldLockAmountAndWriteOutbox() {
        WalletEntity wallet = buildWallet(0, 0, 200, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("SELL", 200);

        auctionBidListener.onAuctionBidSubmitted(event);

        ArgumentCaptor<WalletEntity> walletCaptor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(walletCaptor.capture());
        WalletEntity saved = walletCaptor.getValue();
        assertEquals(0, saved.getAvailableAmount());
        assertEquals(200, saved.getLockedAmount());

        verify(outboxRepository).save(any(OutboxEntity.class));
    }

    // -------------------------------------------------------------------------
    // OptimisticLockException retry
    // -------------------------------------------------------------------------

    @Test
    void optimisticLock_firstAttemptFails_secondSucceeds_shouldRetryAndLockFunds() {
        WalletEntity wallet = buildWallet(10000, 0, 0, 0);

        // First call throws optimistic lock, second call returns wallet
        when(walletRepository.findByUserId(testUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenReturn(wallet);

        AuctionBidSubmittedEvent event = buildEvent("BUY", 3000);
        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        // Wallet and outbox should be saved on the successful 2nd attempt
        verify(walletRepository, times(1)).save(wallet);
        verify(outboxRepository, times(1)).save(any(OutboxEntity.class));
        assertEquals(7000, wallet.getAvailableCurrency());
        assertEquals(3000, wallet.getLockedCurrency());
    }

    @Test
    void optimisticLock_allThreeAttemptsFail_shouldThrowAfterMaxRetries() {
        // All 3 attempts throw ObjectOptimisticLockingFailureException
        when(walletRepository.findByUserId(testUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L));

        AuctionBidSubmittedEvent event = buildEvent("BUY", 3000);
        // After max retries AuctionBidListener re-throws the exception
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> auctionBidListener.onAuctionBidSubmitted(event));

        verify(walletRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        // findByUserId called 3 times (one per retry attempt)
        verify(walletRepository, times(3)).findByUserId(testUserId);
    }

    @Test
    void optimisticLock_twoFailuresThenSuccess_shouldSettleOnThirdAttempt() {
        WalletEntity wallet = buildWallet(5000, 0, 0, 0);

        when(walletRepository.findByUserId(testUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenReturn(wallet);

        AuctionBidSubmittedEvent event = buildEvent("BUY", 2000);
        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        verify(walletRepository, times(1)).save(wallet);
        verify(outboxRepository, times(1)).save(any(OutboxEntity.class));
        assertEquals(3000, wallet.getAvailableCurrency());
        assertEquals(2000, wallet.getLockedCurrency());
    }
}

package com.eap.eap_wallet.application;

import com.eap.common.event.AuctionBidSubmittedEvent;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuctionBidListenerTest {

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private AuctionBidListener auctionBidListener;

    private UUID testUserId;
    private String testAuctionId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testAuctionId = "auction-001";
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
    // BUY: lock currency
    // -------------------------------------------------------------------------

    @Test
    void buy_sufficientCurrency_shouldLockCurrency() {
        // Given
        WalletEntity wallet = buildWallet(10000, 0, 100, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 3000);

        // When
        auctionBidListener.onAuctionBidSubmitted(event);

        // Then
        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();
        assertEquals(7000, saved.getAvailableCurrency());
        assertEquals(3000, saved.getLockedCurrency());
        // energy fields untouched
        assertEquals(100, saved.getAvailableAmount());
        assertEquals(0, saved.getLockedAmount());
    }

    @Test
    void buy_insufficientCurrency_shouldNotSaveAndNotThrow() {
        // Given
        WalletEntity wallet = buildWallet(1000, 0, 100, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 5000); // 5000 > 1000

        // When — should not throw
        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        // Then — wallet must not be modified
        verify(walletRepository, never()).save(any(WalletEntity.class));
        assertEquals(1000, wallet.getAvailableCurrency());
        assertEquals(0, wallet.getLockedCurrency());
    }

    // -------------------------------------------------------------------------
    // SELL: lock amount (energy)
    // -------------------------------------------------------------------------

    @Test
    void sell_sufficientAmount_shouldLockAmount() {
        // Given
        WalletEntity wallet = buildWallet(0, 0, 500, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("SELL", 200);

        // When
        auctionBidListener.onAuctionBidSubmitted(event);

        // Then
        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();
        assertEquals(300, saved.getAvailableAmount());
        assertEquals(200, saved.getLockedAmount());
        // currency fields untouched
        assertEquals(0, saved.getAvailableCurrency());
        assertEquals(0, saved.getLockedCurrency());
    }

    @Test
    void sell_insufficientAmount_shouldNotSaveAndNotThrow() {
        // Given
        WalletEntity wallet = buildWallet(0, 0, 100, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("SELL", 200); // 200 > 100

        // When
        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        // Then
        verify(walletRepository, never()).save(any(WalletEntity.class));
        assertEquals(100, wallet.getAvailableAmount());
        assertEquals(0, wallet.getLockedAmount());
    }

    // -------------------------------------------------------------------------
    // Wallet not found
    // -------------------------------------------------------------------------

    @Test
    void walletNotFound_shouldNotThrowAndNotSave() {
        // Given
        when(walletRepository.findByUserId(testUserId)).thenReturn(null);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 1000);

        // When
        assertDoesNotThrow(() -> auctionBidListener.onAuctionBidSubmitted(event));

        // Then
        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

    // -------------------------------------------------------------------------
    // Exact boundary: available == totalLocked (should succeed)
    // -------------------------------------------------------------------------

    @Test
    void buy_exactBalance_shouldLockCurrency() {
        // Given
        WalletEntity wallet = buildWallet(3000, 0, 0, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("BUY", 3000);

        // When
        auctionBidListener.onAuctionBidSubmitted(event);

        // Then
        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();
        assertEquals(0, saved.getAvailableCurrency());
        assertEquals(3000, saved.getLockedCurrency());
    }

    @Test
    void sell_exactBalance_shouldLockAmount() {
        // Given
        WalletEntity wallet = buildWallet(0, 0, 200, 0);
        when(walletRepository.findByUserId(testUserId)).thenReturn(wallet);
        AuctionBidSubmittedEvent event = buildEvent("SELL", 200);

        // When
        auctionBidListener.onAuctionBidSubmitted(event);

        // Then
        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();
        assertEquals(0, saved.getAvailableAmount());
        assertEquals(200, saved.getLockedAmount());
    }
}

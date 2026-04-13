package com.eap.eap_wallet.application;

import com.eap.common.event.AuctionClearedEvent;
import com.eap.common.event.AuctionClearedEvent.AuctionBidResult;
import com.eap.eap_wallet.configuration.repository.SettlementIdempotencyRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.SettlementIdempotencyEntity;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuctionSettlementListenerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private SettlementIdempotencyRepository settlementIdempotencyRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private AuctionSettlementListener auctionSettlementListener;

    private UUID buyerUserId;
    private UUID sellerUserId;
    private String testAuctionId;

    @BeforeEach
    void setUp() {
        buyerUserId = UUID.randomUUID();
        sellerUserId = UUID.randomUUID();
        testAuctionId = "auction-001";

        // Make TransactionTemplate execute the callback directly
        TransactionStatus mockStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any(DefaultTransactionDefinition.class)))
                .thenReturn(mockStatus);
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private WalletEntity buildWallet(UUID userId, int availableCurrency, int lockedCurrency,
                                     int availableAmount, int lockedAmount) {
        return WalletEntity.builder()
                .id(1L)
                .userId(userId)
                .availableCurrency(availableCurrency)
                .lockedCurrency(lockedCurrency)
                .availableAmount(availableAmount)
                .lockedAmount(lockedAmount)
                .updateTime(LocalDateTime.now())
                .build();
    }

    private AuctionBidResult buildResult(UUID userId, String side,
                                         int bidAmount, int clearedAmount,
                                         int settlementAmount, int originalTotalLocked) {
        return new AuctionBidResult(userId, side, bidAmount, clearedAmount, settlementAmount, originalTotalLocked);
    }

    private AuctionClearedEvent buildEvent(List<AuctionBidResult> results) {
        return AuctionClearedEvent.builder()
                .auctionId(testAuctionId)
                .clearingPrice(100)
                .clearingVolume(50)
                .status("CLEARED")
                .results(results)
                .clearedAt(LocalDateTime.now())
                .build();
    }

    // -------------------------------------------------------------------------
    // BUY: cleared (partial or full)
    // -------------------------------------------------------------------------

    @Test
    void buy_cleared_shouldUnlockCurrencyRefundExcessAndReceiveEnergy() {
        // originalTotalLocked=10000, settlementAmount=6000, clearedAmount=60
        // => refund = 10000-6000 = 4000, receive energy 60
        WalletEntity wallet = buildWallet(buyerUserId, 0, 10000, 0, 0);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(wallet);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 60, 6000, 10000);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();

        assertEquals(0, saved.getLockedCurrency());          // 10000 - 10000 = 0
        assertEquals(4000, saved.getAvailableCurrency());    // 0 + (10000-6000)
        assertEquals(60, saved.getAvailableAmount());        // 0 + 60
        assertEquals(0, saved.getLockedAmount());            // untouched

        // Verify idempotency record saved
        verify(settlementIdempotencyRepository).save(any(SettlementIdempotencyEntity.class));
    }

    // -------------------------------------------------------------------------
    // BUY: not cleared (clearedAmount == 0)
    // -------------------------------------------------------------------------

    @Test
    void buy_notCleared_shouldFullRefundCurrency() {
        // originalTotalLocked=5000, clearedAmount=0 => full refund 5000
        WalletEntity wallet = buildWallet(buyerUserId, 200, 5000, 0, 0);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(wallet);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 50, 0, 0, 5000);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();

        assertEquals(0, saved.getLockedCurrency());           // 5000 - 5000 = 0
        assertEquals(5200, saved.getAvailableCurrency());     // 200 + 5000
        assertEquals(0, saved.getAvailableAmount());          // untouched
    }

    // -------------------------------------------------------------------------
    // SELL: cleared
    // -------------------------------------------------------------------------

    @Test
    void sell_cleared_shouldUnlockEnergyReturnUnsoldAndReceiveCurrency() {
        // originalTotalLocked=200, clearedAmount=150, settlementAmount=15000
        // => unsold = 200-150 = 50
        WalletEntity wallet = buildWallet(sellerUserId, 0, 0, 0, 200);
        when(walletRepository.findByUserId(sellerUserId)).thenReturn(wallet);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, sellerUserId, "SELL")).thenReturn(false);

        AuctionBidResult result = buildResult(sellerUserId, "SELL", 200, 150, 15000, 200);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();

        assertEquals(0, saved.getLockedAmount());             // 200 - 200 = 0
        assertEquals(50, saved.getAvailableAmount());         // 0 + (200-150)
        assertEquals(15000, saved.getAvailableCurrency());    // 0 + 15000
        assertEquals(0, saved.getLockedCurrency());           // untouched
    }

    // -------------------------------------------------------------------------
    // SELL: not cleared
    // -------------------------------------------------------------------------

    @Test
    void sell_notCleared_shouldFullReturnEnergy() {
        // originalTotalLocked=300, clearedAmount=0 => full return 300
        WalletEntity wallet = buildWallet(sellerUserId, 0, 0, 50, 300);
        when(walletRepository.findByUserId(sellerUserId)).thenReturn(wallet);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, sellerUserId, "SELL")).thenReturn(false);

        AuctionBidResult result = buildResult(sellerUserId, "SELL", 300, 0, 0, 300);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();

        assertEquals(0, saved.getLockedAmount());             // 300 - 300 = 0
        assertEquals(350, saved.getAvailableAmount());        // 50 + 300
        assertEquals(0, saved.getAvailableCurrency());        // untouched
    }

    // -------------------------------------------------------------------------
    // Partial fill (pro-rata): BUY cleared partially
    // -------------------------------------------------------------------------

    @Test
    void buy_partiallyCleared_proRata_shouldRefundPartialCurrencyAndReceivePartialEnergy() {
        // originalTotalLocked=10000, settlementAmount=3000, clearedAmount=30
        // => refund = 10000-3000 = 7000
        WalletEntity wallet = buildWallet(buyerUserId, 500, 10000, 0, 0);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(wallet);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 30, 3000, 10000);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        ArgumentCaptor<WalletEntity> captor = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(captor.capture());
        WalletEntity saved = captor.getValue();

        assertEquals(0, saved.getLockedCurrency());
        assertEquals(7500, saved.getAvailableCurrency());  // 500 + 7000
        assertEquals(30, saved.getAvailableAmount());
    }

    // -------------------------------------------------------------------------
    // Idempotency guard: duplicate settlement should be skipped
    // -------------------------------------------------------------------------

    @Test
    void idempotency_alreadySettled_shouldSkipAndNotModifyWallet() {
        // Simulate second delivery: existsByAuctionIdAndUserIdAndSide returns true
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(true);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 60, 6000, 10000);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        // Wallet must not be fetched or saved since we skipped
        verify(walletRepository, never()).findByUserId(any());
        verify(walletRepository, never()).save(any());
        verify(settlementIdempotencyRepository, never()).save(any());
    }

    @Test
    void idempotency_secondCallForSameBuyer_shouldOnlySettleOnce() {
        WalletEntity wallet = buildWallet(buyerUserId, 0, 10000, 0, 0);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(wallet);
        // First call: not yet settled
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 60, 6000, 10000);
        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        verify(walletRepository, times(1)).save(wallet);
        verify(settlementIdempotencyRepository, times(1)).save(any(SettlementIdempotencyEntity.class));

        // Second call: already settled (simulate idempotency record exists)
        reset(walletRepository, settlementIdempotencyRepository);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(true);

        auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result)));

        verify(walletRepository, never()).save(any());
        verify(settlementIdempotencyRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // OptimisticLockException retry: first attempt fails, second succeeds
    // -------------------------------------------------------------------------

    @Test
    void optimisticLock_firstAttemptFails_secondSucceeds_shouldRetryAndSettle() {
        WalletEntity wallet = buildWallet(buyerUserId, 0, 10000, 0, 0);

        // First call throws optimistic lock, second call returns wallet normally
        when(walletRepository.findByUserId(buyerUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenReturn(wallet);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 60, 6000, 10000);
        assertDoesNotThrow(() ->
                auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result))));

        // Wallet should be saved on the successful 2nd attempt
        verify(walletRepository, times(1)).save(wallet);
        assertEquals(4000, wallet.getAvailableCurrency()); // 0 + (10000-6000)
        assertEquals(60, wallet.getAvailableAmount());
    }

    @Test
    void optimisticLock_allThreeAttemptsFail_shouldLogErrorAndContinue() {
        // All 3 attempts throw ObjectOptimisticLockingFailureException
        when(walletRepository.findByUserId(buyerUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L));
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 60, 6000, 10000);
        // Should not throw — logs error and continues
        assertDoesNotThrow(() ->
                auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result))));

        // Wallet save never called because all 3 attempts failed before reaching save
        verify(walletRepository, never()).save(any());
        // findByUserId called 3 times (one per retry attempt)
        verify(walletRepository, times(3)).findByUserId(buyerUserId);
    }

    @Test
    void optimisticLock_oneParticipantFails3Times_otherParticipantsStillSettle() {
        WalletEntity sellerWallet = buildWallet(sellerUserId, 0, 0, 0, 200);

        // buyerUserId always throws optimistic lock
        when(walletRepository.findByUserId(buyerUserId))
                .thenThrow(new ObjectOptimisticLockingFailureException("WalletEntity", 1L));
        // sellerUserId succeeds
        when(walletRepository.findByUserId(sellerUserId)).thenReturn(sellerWallet);

        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, sellerUserId, "SELL")).thenReturn(false);

        AuctionBidResult buyerResult = buildResult(buyerUserId, "BUY", 100, 60, 6000, 10000);
        AuctionBidResult sellerResult = buildResult(sellerUserId, "SELL", 200, 200, 20000, 200);

        assertDoesNotThrow(() ->
                auctionSettlementListener.handleAuctionCleared(buildEvent(Arrays.asList(buyerResult, sellerResult))));

        // Only seller wallet is saved
        verify(walletRepository, times(1)).save(sellerWallet);
        // Seller settled correctly
        assertEquals(0, sellerWallet.getLockedAmount());
        assertEquals(20000, sellerWallet.getAvailableCurrency());
    }

    // -------------------------------------------------------------------------
    // Multiple results: one failure does not affect others
    // -------------------------------------------------------------------------

    @Test
    void multipleResults_oneWalletNotFound_othersShouldStillSettle() {
        UUID missingUserId = UUID.randomUUID();

        WalletEntity buyerWallet = buildWallet(buyerUserId, 0, 5000, 0, 0);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(buyerWallet);
        when(walletRepository.findByUserId(missingUserId)).thenReturn(null);

        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, missingUserId, "BUY")).thenReturn(false);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult buyerResult = buildResult(buyerUserId, "BUY", 50, 50, 5000, 5000);
        AuctionBidResult missingResult = buildResult(missingUserId, "BUY", 50, 50, 5000, 5000);

        auctionSettlementListener.handleAuctionCleared(buildEvent(Arrays.asList(missingResult, buyerResult)));

        // Only buyerWallet should be saved
        verify(walletRepository, times(1)).save(any(WalletEntity.class));
        verify(walletRepository, times(1)).save(buyerWallet);
    }

    @Test
    void multipleResults_batchSettlement_allShouldSettle() {
        WalletEntity buyerWallet = buildWallet(buyerUserId, 0, 10000, 0, 0);
        WalletEntity sellerWallet = buildWallet(sellerUserId, 0, 0, 0, 200);

        when(walletRepository.findByUserId(buyerUserId)).thenReturn(buyerWallet);
        when(walletRepository.findByUserId(sellerUserId)).thenReturn(sellerWallet);

        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, sellerUserId, "SELL")).thenReturn(false);

        AuctionBidResult buyerResult = buildResult(buyerUserId, "BUY", 100, 100, 10000, 10000);
        AuctionBidResult sellerResult = buildResult(sellerUserId, "SELL", 200, 200, 20000, 200);

        auctionSettlementListener.handleAuctionCleared(buildEvent(Arrays.asList(buyerResult, sellerResult)));

        verify(walletRepository, times(2)).save(any(WalletEntity.class));

        // Buyer: fully cleared, no excess, receive 100 energy
        assertEquals(0, buyerWallet.getLockedCurrency());
        assertEquals(0, buyerWallet.getAvailableCurrency());  // 0 + (10000-10000)
        assertEquals(100, buyerWallet.getAvailableAmount());

        // Seller: fully cleared, no unsold, receive 20000 currency
        assertEquals(0, sellerWallet.getLockedAmount());
        assertEquals(0, sellerWallet.getAvailableAmount());   // 0 + (200-200)
        assertEquals(20000, sellerWallet.getAvailableCurrency());
    }

    // -------------------------------------------------------------------------
    // Wallet not found: log error and continue
    // -------------------------------------------------------------------------

    @Test
    void walletNotFound_shouldNotThrowAndSkipResult() {
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(null);

        AuctionBidResult result = buildResult(buyerUserId, "BUY", 100, 100, 10000, 10000);
        assertDoesNotThrow(() ->
                auctionSettlementListener.handleAuctionCleared(buildEvent(List.of(result))));

        verify(walletRepository, never()).save(any(WalletEntity.class));
    }

    // -------------------------------------------------------------------------
    // Empty results list: no-op
    // -------------------------------------------------------------------------

    @Test
    void emptyResults_shouldNotSaveAnyWallet() {
        AuctionClearedEvent event = buildEvent(Collections.emptyList());
        assertDoesNotThrow(() -> auctionSettlementListener.handleAuctionCleared(event));
        verify(walletRepository, never()).findByUserId(any());
        verify(walletRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Exception in one result does not stop others
    // -------------------------------------------------------------------------

    @Test
    void oneResultThrowsException_othersShouldStillSettle() {
        UUID faultyUserId = UUID.randomUUID();

        WalletEntity buyerWallet = buildWallet(buyerUserId, 0, 5000, 0, 0);
        when(walletRepository.findByUserId(buyerUserId)).thenReturn(buyerWallet);
        when(walletRepository.findByUserId(faultyUserId)).thenThrow(new RuntimeException("DB error"));

        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, faultyUserId, "BUY")).thenReturn(false);
        when(settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                testAuctionId, buyerUserId, "BUY")).thenReturn(false);

        AuctionBidResult faultyResult = buildResult(faultyUserId, "BUY", 50, 50, 5000, 5000);
        AuctionBidResult buyerResult = buildResult(buyerUserId, "BUY", 50, 50, 5000, 5000);

        assertDoesNotThrow(() ->
                auctionSettlementListener.handleAuctionCleared(buildEvent(Arrays.asList(faultyResult, buyerResult))));

        verify(walletRepository, times(1)).save(buyerWallet);
    }
}

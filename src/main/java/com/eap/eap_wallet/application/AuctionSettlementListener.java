package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.SettlementIdempotencyRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.SettlementIdempotencyEntity;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.AuctionClearedEvent;
import com.eap.common.event.AuctionClearedEvent.AuctionBidResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.eap.common.constants.RabbitMQConstants.WALLET_AUCTION_CLEARED_QUEUE;

/**
 * Listener for auction clearing results.
 *
 * Processes each participant's wallet settlement after auction clearing:
 *
 * BUY cleared:
 *   lockedCurrency -= originalTotalLocked
 *   availableCurrency += (originalTotalLocked - settlementAmount)  // refund excess
 *   availableAmount += clearedAmount                               // receive energy
 *
 * BUY not cleared:
 *   lockedCurrency -= originalTotalLocked
 *   availableCurrency += originalTotalLocked                       // full refund
 *
 * SELL cleared:
 *   lockedAmount -= originalTotalLocked
 *   availableAmount += (originalTotalLocked - clearedAmount)       // return unsold
 *   availableCurrency += settlementAmount                          // receive payment
 *
 * SELL not cleared:
 *   lockedAmount -= originalTotalLocked
 *   availableAmount += originalTotalLocked                         // full return
 *
 * Each result is processed in its own try-catch so one failure does not
 * affect other participants.
 */
@Component
@Slf4j
public class AuctionSettlementListener {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private SettlementIdempotencyRepository settlementIdempotencyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @RabbitListener(queues = WALLET_AUCTION_CLEARED_QUEUE)
    public void handleAuctionCleared(AuctionClearedEvent event) {
        log.info("Received AuctionClearedEvent: auctionId={}, status={}, MCP={}, MCV={}, results={}",
                event.getAuctionId(), event.getStatus(), event.getClearingPrice(),
                event.getClearingVolume(),
                event.getResults() != null ? event.getResults().size() : 0);

        if (event.getResults() == null || event.getResults().isEmpty()) {
            log.info("No results to settle for auctionId={}", event.getAuctionId());
            return;
        }

        int settledCount = 0;
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        for (AuctionBidResult result : event.getResults()) {
            int maxRetries = 3;
            boolean settled = false;
            for (int attempt = 1; attempt <= maxRetries && !settled; attempt++) {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        // Idempotency guard: skip if already settled
                        if (settlementIdempotencyRepository.existsByAuctionIdAndUserIdAndSide(
                                event.getAuctionId(), result.getUserId(), result.getSide())) {
                            log.info("Settlement already processed, skipping: auctionId={}, userId={}, side={}",
                                    event.getAuctionId(), result.getUserId(), result.getSide());
                            return;
                        }

                        WalletEntity wallet = walletRepository.findByUserId(result.getUserId());
                        if (wallet == null) {
                            throw new IllegalStateException("Wallet not found for userId=" + result.getUserId());
                        }

                        if ("BUY".equals(result.getSide())) {
                            settleBuyer(wallet, result);
                        } else if ("SELL".equals(result.getSide())) {
                            settleSeller(wallet, result);
                        } else {
                            throw new IllegalArgumentException("Unknown side '" + result.getSide() + "' for userId=" + result.getUserId());
                        }

                        walletRepository.save(wallet);

                        // Record idempotency entry (same transaction as wallet save)
                        settlementIdempotencyRepository.save(SettlementIdempotencyEntity.builder()
                                .auctionId(event.getAuctionId())
                                .userId(result.getUserId())
                                .side(result.getSide())
                                .build());
                    });
                    settled = true;
                    settledCount++;
                } catch (ObjectOptimisticLockingFailureException e) {
                    if (attempt == maxRetries) {
                        log.error("Settlement failed after {} retries due to optimistic lock conflict: auctionId={}, userId={}",
                                maxRetries, event.getAuctionId(), result.getUserId(), e);
                    } else {
                        log.warn("Optimistic lock conflict on settlement attempt {}/{}: auctionId={}, userId={}, retrying...",
                                attempt, maxRetries, event.getAuctionId(), result.getUserId());
                    }
                } catch (Exception e) {
                    log.error("Settlement failed for userId={}: {}", result.getUserId(), e.getMessage(), e);
                    break; // non-retryable error, move to next result
                }
            }
        }

        log.info("Auction settlement complete: auctionId={}, MCP={}, MCV={}, settled={}/{}",
                event.getAuctionId(), event.getClearingPrice(), event.getClearingVolume(),
                settledCount, event.getResults().size());
    }

    private void settleBuyer(WalletEntity wallet, AuctionBidResult result) {
        // Unlock original locked currency
        wallet.setLockedCurrency(wallet.getLockedCurrency() - result.getOriginalTotalLocked());

        if (result.getClearedAmount() > 0) {
            // Partially or fully cleared: refund excess currency, receive energy
            Integer refund = result.getOriginalTotalLocked() - result.getSettlementAmount();
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() + refund);
            wallet.setAvailableAmount(wallet.getAvailableAmount() + result.getClearedAmount());

            log.info("BUY settled: userId={}, paid={}, refund={}, received energy={}",
                    result.getUserId(), result.getSettlementAmount(), refund, result.getClearedAmount());
        } else {
            // Not cleared: full refund
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() + result.getOriginalTotalLocked());

            log.info("BUY not cleared, full refund: userId={}, refund={}",
                    result.getUserId(), result.getOriginalTotalLocked());
        }
    }

    private void settleSeller(WalletEntity wallet, AuctionBidResult result) {
        // Unlock original locked energy amount
        wallet.setLockedAmount(wallet.getLockedAmount() - result.getOriginalTotalLocked());

        if (result.getClearedAmount() > 0) {
            // Partially or fully cleared: return unsold energy, receive payment
            Integer unsold = result.getOriginalTotalLocked() - result.getClearedAmount();
            wallet.setAvailableAmount(wallet.getAvailableAmount() + unsold);
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() + result.getSettlementAmount());

            log.info("SELL settled: userId={}, sold={}, unsold returned={}, received currency={}",
                    result.getUserId(), result.getClearedAmount(), unsold, result.getSettlementAmount());
        } else {
            // Not cleared: full return of energy
            wallet.setAvailableAmount(wallet.getAvailableAmount() + result.getOriginalTotalLocked());

            log.info("SELL not cleared, full return: userId={}, returned={}",
                    result.getUserId(), result.getOriginalTotalLocked());
        }
    }
}

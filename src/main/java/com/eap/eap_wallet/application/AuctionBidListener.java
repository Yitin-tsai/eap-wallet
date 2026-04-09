package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.AuctionBidSubmittedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.eap.common.constants.RabbitMQConstants.WALLET_AUCTION_BID_SUBMITTED_QUEUE;

/**
 * Listener for auction bid submitted events.
 *
 * Locks funds in the user's wallet when an auction bid is submitted:
 * - BUY: locks currency (availableCurrency -= totalLocked, lockedCurrency += totalLocked)
 * - SELL: locks energy amount (availableAmount -= totalLocked, lockedAmount += totalLocked)
 *
 * If wallet not found or balance insufficient, logs a warning and returns
 * without throwing an exception. The clearing result will show 0 allocation
 * for underfunded bids.
 */
@Component
@Slf4j
public class AuctionBidListener {

    @Autowired
    private WalletRepository walletRepository;

    @RabbitListener(queues = WALLET_AUCTION_BID_SUBMITTED_QUEUE)
    @Transactional
    public void onAuctionBidSubmitted(AuctionBidSubmittedEvent event) {
        log.info("Received AuctionBidSubmittedEvent: auctionId={}, userId={}, side={}, totalLocked={}",
                event.getAuctionId(), event.getUserId(), event.getSide(), event.getTotalLocked());

        WalletEntity wallet = walletRepository.findByUserId(event.getUserId());
        if (wallet == null) {
            log.error("Wallet not found for userId={}, cannot lock auction bid funds", event.getUserId());
            return;
        }

        Integer totalLocked = event.getTotalLocked();

        if ("BUY".equals(event.getSide())) {
            if (wallet.getAvailableCurrency() < totalLocked) {
                log.warn("Insufficient currency for auction bid: userId={}, required={}, available={}",
                        event.getUserId(), totalLocked, wallet.getAvailableCurrency());
                return;
            }
            wallet.setAvailableCurrency(wallet.getAvailableCurrency() - totalLocked);
            wallet.setLockedCurrency(wallet.getLockedCurrency() + totalLocked);
        } else if ("SELL".equals(event.getSide())) {
            if (wallet.getAvailableAmount() < totalLocked) {
                log.warn("Insufficient energy amount for auction bid: userId={}, required={}, available={}",
                        event.getUserId(), totalLocked, wallet.getAvailableAmount());
                return;
            }
            wallet.setAvailableAmount(wallet.getAvailableAmount() - totalLocked);
            wallet.setLockedAmount(wallet.getLockedAmount() + totalLocked);
        } else {
            log.error("Unknown bid side: {}", event.getSide());
            return;
        }

        walletRepository.save(wallet);
        log.info("Auction bid funds locked: userId={}, side={}, locked={}",
                event.getUserId(), event.getSide(), totalLocked);
    }
}

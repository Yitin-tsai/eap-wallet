package com.eap.eap_wallet.application;

import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.eap.eap_wallet.domain.entity.WalletEntity;
import com.eap.common.event.AuctionBidConfirmedEvent;
import com.eap.common.event.AuctionBidSubmittedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.eap.common.constants.RabbitMQConstants.*;

/**
 * Listener for auction bid submitted events.
 *
 * Follows the wallet-first + outbox pattern (same as CreateOrderListener):
 * 1. Validate wallet balance
 * 2. Lock funds (wallet.save())
 * 3. Write AuctionBidConfirmedEvent to outbox (same DB transaction)
 *
 * On failure (wallet not found / insufficient balance):
 * - Logs error and returns without writing to outbox
 * - The bid will NOT reach matchEngine (correct: unfunded bids must not enter auction)
 */
@Component
@Slf4j
public class AuctionBidListener {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

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
                log.warn("Insufficient energy for auction bid: userId={}, required={}, available={}",
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

        // Write confirmed event to outbox (same transaction as wallet lock)
        AuctionBidConfirmedEvent confirmedEvent = AuctionBidConfirmedEvent.builder()
                .auctionId(event.getAuctionId())
                .userId(event.getUserId())
                .side(event.getSide())
                .steps(event.getSteps())
                .totalLocked(totalLocked)
                .createdAt(event.getCreatedAt())
                .build();

        try {
            String payload = objectMapper.writeValueAsString(confirmedEvent);
            outboxRepository.save(new OutboxEntity("AuctionBidConfirmedEvent", AUCTION_BID_CONFIRMED_KEY, payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize AuctionBidConfirmedEvent", e);
        }

        log.info("Auction bid funds locked and confirmed event written to outbox: userId={}, side={}, locked={}",
                event.getUserId(), event.getSide(), totalLocked);
    }
}

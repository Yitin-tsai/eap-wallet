package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.TradeSettlementRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.eap.eap_wallet.domain.entity.TradeSettlementEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static com.eap.common.constants.RabbitMQConstants.TRADE_WALLET_SETTLED_KEY;
import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradeExecutedListener {

    private final WalletRepository walletRepository;
    private final TradeSettlementRepository tradeSettlementRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @RabbitListener(
            queues = WALLET_TRADE_EXECUTED_QUEUE,
            concurrency = "${eap.wallet.listeners.trade-executed.concurrency:4}")
    public void handleTradeExecuted(TradeExecutedEvent event) {
        log.debug("Received TradeExecutedEvent for wallet settlement: tradeId={}, legacyMatchId={}",
                event.getTradeId(), event.getLegacyMatchId());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                txTemplate.executeWithoutResult(status -> settle(event));
                return;
            } catch (DataIntegrityViolationException e) {
                log.info("Duplicate TradeExecutedEvent settlement skipped: tradeId={}", event.getTradeId());
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == maxRetries) {
                    log.error("Trade settlement failed after optimistic lock retries: tradeId={}",
                            event.getTradeId(), e);
                    throw e;
                }
                log.warn("Optimistic lock conflict on trade settlement attempt {}/{}: tradeId={}",
                        attempt, maxRetries, event.getTradeId());
            }
        }
    }

    private void settle(TradeExecutedEvent event) {
        if (tradeSettlementRepository.existsByTradeId(event.getTradeId())) {
            log.debug("Trade already settled, skipping duplicate: tradeId={}", event.getTradeId());
            return;
        }

        int dealCurrency = event.getDealPrice() * event.getQuantity();
        int originalLockedCurrency = event.getOriginBuyerPrice() * event.getQuantity();
        int refundCurrency = originalLockedCurrency - dealCurrency;

        int buyerUpdated = walletRepository.settleTradeForBuyer(
                event.getBuyerId(),
                originalLockedCurrency,
                refundCurrency,
                event.getQuantity());
        if (buyerUpdated != 1) {
            throw new IllegalStateException("Buyer locked currency is insufficient for trade settlement: tradeId="
                    + event.getTradeId() + ", buyerId=" + event.getBuyerId());
        }

        int sellerUpdated = walletRepository.settleTradeForSeller(
                event.getSellerId(),
                event.getQuantity(),
                dealCurrency);
        if (sellerUpdated != 1) {
            throw new IllegalStateException("Seller locked amount is insufficient for trade settlement: tradeId="
                    + event.getTradeId() + ", sellerId=" + event.getSellerId());
        }

        LocalDateTime settledAt = event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt();
        tradeSettlementRepository.save(new TradeSettlementEntity(
                event.getTradeId(), event.getLegacyMatchId(), settledAt));
        writeWalletTradeSettledOutbox(event, originalLockedCurrency, refundCurrency, dealCurrency, settledAt);

        log.info("Trade wallet settlement completed: tradeId={}, buyerId={}, sellerId={}, dealCurrency={}, quantity={}",
                event.getTradeId(), event.getBuyerId(), event.getSellerId(), dealCurrency, event.getQuantity());
    }

    private void writeWalletTradeSettledOutbox(
            TradeExecutedEvent event,
            int originalLockedCurrency,
            int refundCurrency,
            int dealCurrency,
            LocalDateTime settledAt) {
        WalletTradeSettledEvent settledEvent = WalletTradeSettledEvent.builder()
                .tradeId(event.getTradeId())
                .legacyMatchId(event.getLegacyMatchId())
                .buyerId(event.getBuyerId())
                .sellerId(event.getSellerId())
                .buyerOrderId(event.getBuyerOrderId())
                .sellerOrderId(event.getSellerOrderId())
                .dealPrice(event.getDealPrice())
                .quantity(event.getQuantity())
                .buyerLockedCurrency(originalLockedCurrency)
                .buyerRefundCurrency(refundCurrency)
                .sellerReceivedCurrency(dealCurrency)
                .settledAt(settledAt)
                .build();
        try {
            outboxRepository.save(new OutboxEntity(
                    "WalletTradeSettledEvent",
                    TRADE_WALLET_SETTLED_KEY,
                    objectMapper.writeValueAsString(settledEvent)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WalletTradeSettledEvent: tradeId="
                    + event.getTradeId(), e);
        }
    }
}

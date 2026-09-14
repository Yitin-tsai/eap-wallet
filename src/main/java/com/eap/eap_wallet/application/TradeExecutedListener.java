package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradeExecutedListener {

    private final WalletMessageInbox inbox;
    private final WalletMetrics walletMetrics;

    @RabbitListener(
            queues = WALLET_TRADE_EXECUTED_QUEUE,
            concurrency = "${eap.wallet.listeners.trade-executed.concurrency:4}")
    public void handleTradeExecuted(TradeExecutedEvent event) {
        long processingStartedAt = System.nanoTime();
        walletMetrics.tradeSettlementConsumed();
        log.debug("Received TradeExecutedEvent for wallet settlement: tradeId={}, legacyMatchId={}",
                event.getTradeId(), event.getLegacyMatchId());

        try {
            WalletMessageInbox.ReceiveOutcome outcome = inbox.receiveTradeExecuted(event);
            if (outcome == WalletMessageInbox.ReceiveOutcome.CONFLICT) {
                log.error("Durable Wallet inbox trade identity conflict: tradeId={}", event.getTradeId());
            } else if (outcome == WalletMessageInbox.ReceiveOutcome.DUPLICATE) {
                walletMetrics.tradeInboxDuplicate();
            }
        } finally {
            walletMetrics.recordTradeSettlementProcessing(
                    Duration.ofNanos(System.nanoTime() - processingStartedAt));
        }
    }

}

package com.eap.eap_wallet.configuration.recovery;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.recovery.BrokerReplayPreflightDecision;
import com.eap.common.recovery.BrokerReplayPreflightRequest;
import com.eap.common.recovery.BrokerReplayPreflightResult;
import com.eap.eap_wallet.application.WalletMessageInbox;
import com.eap.eap_wallet.application.WalletTradeEventValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import static com.eap.common.constants.RabbitMQConstants.TRADE_EXCHANGE;
import static com.eap.common.constants.RabbitMQConstants.TRADE_EXECUTED_KEY;
import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;

@Service
public class WalletBrokerReplayPreflightService {

    private final WalletMessageInbox inbox;
    private final ObjectMapper objectMapper;

    public WalletBrokerReplayPreflightService(WalletMessageInbox inbox, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.objectMapper = objectMapper;
    }

    public BrokerReplayPreflightResult inspect(BrokerReplayPreflightRequest request) {
        if (!WALLET_TRADE_EXECUTED_QUEUE.equals(request.sourceQueue())
                || !TRADE_EXCHANGE.equals(request.originalExchange())
                || !TRADE_EXECUTED_KEY.equals(request.originalRoutingKey())) {
            return result(BrokerReplayPreflightDecision.UNSUPPORTED_ROUTE, false,
                    "Wallet only permits broker replay for its exact TradeExecuted topology",
                    null, null);
        }

        final TradeExecutedEvent event;
        try {
            event = objectMapper.readValue(request.payload(), TradeExecutedEvent.class);
            WalletTradeEventValidator.validate(event);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return result(BrokerReplayPreflightDecision.INVALID_PAYLOAD, false,
                    "TradeExecuted payload is not safe to replay: " + invalid.getMessage(),
                    null, null);
        }

        WalletMessageInbox.TradeInboxSnapshot snapshot = inbox.inspectTradeExecuted(event);
        if (!snapshot.exists()) {
            return result(BrokerReplayPreflightDecision.ELIGIBLE, true,
                    "Wallet has no durable intake for this valid trade", event.getTradeId(), null);
        }
        if (!snapshot.payloadMatches() || snapshot.conflictDetected()) {
            return result(BrokerReplayPreflightDecision.IDENTITY_CONFLICT, false,
                    "Wallet already has the trade identity with a different payload",
                    event.getTradeId(), snapshot.status());
        }
        if ("APPLIED".equals(snapshot.status())) {
            return result(BrokerReplayPreflightDecision.ALREADY_APPLIED, false,
                    "Wallet already applied this exact trade", event.getTradeId(), snapshot.status());
        }
        if ("PENDING".equals(snapshot.status())
                || "IN_PROGRESS".equals(snapshot.status())
                || "FAILED_RETRYABLE".equals(snapshot.status())) {
            return result(BrokerReplayPreflightDecision.ALREADY_DURABLE, false,
                    "Wallet durable inbox already owns retry for this exact trade",
                    event.getTradeId(), snapshot.status());
        }
        return result(BrokerReplayPreflightDecision.PERMANENT_FAILURE, false,
                "Wallet durable inbox marks this exact trade as permanently failed",
                event.getTradeId(), snapshot.status());
    }

    private BrokerReplayPreflightResult result(
            BrokerReplayPreflightDecision decision,
            boolean safeToReplay,
            String reason,
            String messageId,
            String inboxStatus) {
        return new BrokerReplayPreflightResult(
                decision, safeToReplay, reason, messageId, inboxStatus);
    }
}

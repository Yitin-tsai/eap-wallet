package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;

import static com.eap.common.event.TradeExecutedEvent.MAX_TRADE_ID_LENGTH;

public final class WalletTradeEventValidator {

    private WalletTradeEventValidator() {
    }

    public static void validate(TradeExecutedEvent event) {
        if (event == null || event.getTradeId() == null || event.getTradeId().isBlank()
                || event.getBuyerId() == null || event.getSellerId() == null
                || event.getBuyerOrderId() == null || event.getSellerOrderId() == null) {
            throw new IllegalArgumentException("TradeExecutedEvent settlement identifiers are required");
        }
        if (event.getTradeId().length() > MAX_TRADE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "TradeExecutedEvent tradeId exceeds " + MAX_TRADE_ID_LENGTH + " characters");
        }
        if (event.getBuyerId().equals(event.getSellerId())) {
            throw new IllegalArgumentException("TradeExecutedEvent cannot settle a self-trade");
        }
        if (event.getBuyerOrderId().equals(event.getSellerOrderId())) {
            throw new IllegalArgumentException("TradeExecutedEvent buyer and seller orders must differ");
        }
        if (event.getOriginBuyerPrice() == null || event.getOriginBuyerPrice() <= 0
                || event.getOriginSellerPrice() == null || event.getOriginSellerPrice() <= 0
                || event.getDealPrice() == null || event.getDealPrice() <= 0
                || event.getQuantity() == null || event.getQuantity() <= 0) {
            throw new IllegalArgumentException("TradeExecutedEvent settlement values must be positive");
        }
        if (event.getDealPrice() > event.getOriginBuyerPrice()) {
            throw new IllegalArgumentException("Trade deal price exceeds buyer limit price");
        }
        if (event.getDealPrice() < event.getOriginSellerPrice()) {
            throw new IllegalArgumentException("Trade deal price is below seller limit price");
        }
    }
}

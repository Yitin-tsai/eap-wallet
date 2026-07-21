package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WalletTradeSettlementAppender {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final WalletMetrics walletMetrics;

    public SettlementOutcome append(
            TradeExecutedEvent event,
            LocalDateTime settledAt) {
        int dealCurrency = event.getDealPrice() * event.getQuantity();
        int originalLockedCurrency = event.getOriginBuyerPrice() * event.getQuantity();
        int refundCurrency = originalLockedCurrency - dealCurrency;

        int[] insertedSettlements = {0};
        int[] updatedBuyers = {0};
        int[] updatedSellers = {0};
        long cteStartedAt = System.nanoTime();
        try {
            jdbcTemplate.query("""
                WITH settlement AS (
                    INSERT INTO wallet_service.trade_settlements
                        (trade_id, legacy_match_id, settled_at,
                         buyer_id, seller_id, buyer_order_id, seller_order_id,
                         deal_price, quantity, buyer_locked_currency, buyer_refund_currency,
                         seller_received_currency, event_status, attempt_count,
                         next_retry_at, updated_at)
                    VALUES
                        (:tradeId, :legacyMatchId, :settledAt,
                         :buyerId, :sellerId, :buyerOrderId, :sellerOrderId,
                         :dealPrice, :quantity, :originalLockedCurrency, :refundCurrency,
                         :dealCurrency, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (trade_id) DO NOTHING
                    RETURNING trade_id
                ),
                buyer_update AS (
                    UPDATE wallet_service.wallets
                    SET locked_currency = locked_currency - :originalLockedCurrency,
                        available_currency = available_currency + :refundCurrency,
                        available_amount = available_amount + :quantity,
                        version = version + 1,
                        update_time = CURRENT_TIMESTAMP
                    WHERE user_id = :buyerId
                      AND EXISTS (SELECT 1 FROM settlement)
                      AND locked_currency >= :originalLockedCurrency
                    RETURNING 1
                ),
                seller_update AS (
                    UPDATE wallet_service.wallets
                    SET locked_amount = locked_amount - :quantity,
                        available_currency = available_currency + :dealCurrency,
                        version = version + 1,
                        update_time = CURRENT_TIMESTAMP
                    WHERE user_id = :sellerId
                      AND EXISTS (SELECT 1 FROM settlement)
                      AND locked_amount >= :quantity
                    RETURNING 1
                )
                SELECT
                    (SELECT COUNT(*) FROM settlement) AS inserted_settlements,
                    (SELECT COUNT(*) FROM buyer_update) AS updated_buyers,
                    (SELECT COUNT(*) FROM seller_update) AS updated_sellers
                """, new MapSqlParameterSource()
                .addValue("tradeId", event.getTradeId())
                .addValue("legacyMatchId", event.getLegacyMatchId())
                .addValue("settledAt", settledAt)
                .addValue("buyerId", event.getBuyerId())
                .addValue("sellerId", event.getSellerId())
                .addValue("buyerOrderId", event.getBuyerOrderId())
                .addValue("sellerOrderId", event.getSellerOrderId())
                .addValue("dealPrice", event.getDealPrice())
                .addValue("originalLockedCurrency", originalLockedCurrency)
                .addValue("refundCurrency", refundCurrency)
                .addValue("dealCurrency", dealCurrency)
                .addValue("quantity", event.getQuantity()), rs -> {
                    insertedSettlements[0] = rs.getInt("inserted_settlements");
                    updatedBuyers[0] = rs.getInt("updated_buyers");
                    updatedSellers[0] = rs.getInt("updated_sellers");
                });
        } finally {
            walletMetrics.recordTradeSettlementCte(Duration.ofNanos(System.nanoTime() - cteStartedAt));
        }

        SettlementOutcome outcome = new SettlementOutcome(
                insertedSettlements[0],
                updatedBuyers[0],
                updatedSellers[0],
                originalLockedCurrency,
                refundCurrency,
                dealCurrency,
                settledAt);
        if (outcome.duplicate()) {
            return outcome;
        }
        if (!outcome.completed()) {
            throw new IllegalStateException("Wallet trade settlement did not update both wallets and outbox: tradeId="
                    + event.getTradeId()
                    + ", insertedSettlements=" + outcome.insertedSettlements()
                    + ", updatedBuyers=" + outcome.updatedBuyers()
                    + ", updatedSellers=" + outcome.updatedSellers());
        }
        return outcome;
    }

    public BatchSettlementOutcome appendBatch(List<TradeExecutedEvent> events) {
        if (events == null || events.isEmpty()) {
            return new BatchSettlementOutcome(0, 0, 0, 0, 0);
        }

        StringBuilder values = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                values.append(", ");
            }
            values.append("(:tradeId").append(i).append("::varchar")
                    .append(", :legacyMatchId").append(i).append("::integer")
                    .append(", :settledAt").append(i).append("::timestamp")
                    .append(", :buyerId").append(i).append("::uuid")
                    .append(", :sellerId").append(i).append("::uuid")
                    .append(", :buyerOrderId").append(i).append("::uuid")
                    .append(", :sellerOrderId").append(i).append("::uuid")
                    .append(", :dealPrice").append(i).append("::integer")
                    .append(", :quantity").append(i).append("::integer")
                    .append(", :originalLockedCurrency").append(i).append("::integer")
                    .append(", :refundCurrency").append(i).append("::integer")
                    .append(", :dealCurrency").append(i).append("::integer")
                    .append(")");
            addBatchParams(params, events.get(i), i);
        }

        int[] existingSettlements = {0};
        int[] insertedSettlements = {0};
        int[] updatedBuyers = {0};
        int[] updatedSellers = {0};
        long cteStartedAt = System.nanoTime();
        try {
            jdbcTemplate.query("""
                WITH input(trade_id, legacy_match_id, settled_at,
                           buyer_id, seller_id, buyer_order_id, seller_order_id,
                           deal_price, quantity, original_locked_currency, refund_currency,
                           deal_currency) AS (
                    VALUES
                """ + values + """
                ),
                existing_settlements AS (
                    SELECT COUNT(*) AS count
                    FROM wallet_service.trade_settlements existing
                    JOIN input ON input.trade_id = existing.trade_id
                ),
                settlement AS (
                    INSERT INTO wallet_service.trade_settlements
                        (trade_id, legacy_match_id, settled_at,
                         buyer_id, seller_id, buyer_order_id, seller_order_id,
                         deal_price, quantity, buyer_locked_currency, buyer_refund_currency,
                         seller_received_currency, event_status, attempt_count,
                         next_retry_at, updated_at)
                    SELECT trade_id, legacy_match_id, settled_at,
                           buyer_id, seller_id, buyer_order_id, seller_order_id,
                           deal_price, quantity, original_locked_currency, refund_currency,
                           deal_currency, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM input
                    WHERE (SELECT count FROM existing_settlements) = 0
                    ON CONFLICT (trade_id) DO NOTHING
                    RETURNING trade_id
                ),
                buyer_update AS (
                    UPDATE wallet_service.wallets wallet
                    SET locked_currency = wallet.locked_currency - input.original_locked_currency,
                        available_currency = wallet.available_currency + input.refund_currency,
                        available_amount = wallet.available_amount + input.quantity,
                        version = wallet.version + 1,
                        update_time = CURRENT_TIMESTAMP
                    FROM input
                    JOIN settlement ON settlement.trade_id = input.trade_id
                    WHERE wallet.user_id = input.buyer_id
                      AND wallet.locked_currency >= input.original_locked_currency
                    RETURNING 1
                ),
                seller_update AS (
                    UPDATE wallet_service.wallets wallet
                    SET locked_amount = wallet.locked_amount - input.quantity,
                        available_currency = wallet.available_currency + input.deal_currency,
                        version = wallet.version + 1,
                        update_time = CURRENT_TIMESTAMP
                    FROM input
                    JOIN settlement ON settlement.trade_id = input.trade_id
                    WHERE wallet.user_id = input.seller_id
                      AND wallet.locked_amount >= input.quantity
                    RETURNING 1
                )
                SELECT
                    (SELECT count FROM existing_settlements) AS existing_settlements,
                    (SELECT COUNT(*) FROM settlement) AS inserted_settlements,
                    (SELECT COUNT(*) FROM buyer_update) AS updated_buyers,
                    (SELECT COUNT(*) FROM seller_update) AS updated_sellers
                """, params, rs -> {
                    existingSettlements[0] = rs.getInt("existing_settlements");
                    insertedSettlements[0] = rs.getInt("inserted_settlements");
                    updatedBuyers[0] = rs.getInt("updated_buyers");
                    updatedSellers[0] = rs.getInt("updated_sellers");
                });
        } finally {
            walletMetrics.recordTradeSettlementCte(Duration.ofNanos(System.nanoTime() - cteStartedAt));
        }

        return new BatchSettlementOutcome(
                events.size(),
                existingSettlements[0],
                insertedSettlements[0],
                updatedBuyers[0],
                updatedSellers[0]);
    }

    private void addBatchParams(MapSqlParameterSource params, TradeExecutedEvent event, int index) {
        int dealCurrency = event.getDealPrice() * event.getQuantity();
        int originalLockedCurrency = event.getOriginBuyerPrice() * event.getQuantity();
        int refundCurrency = originalLockedCurrency - dealCurrency;
        LocalDateTime settledAt = event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt();

        params.addValue("tradeId" + index, event.getTradeId())
                .addValue("legacyMatchId" + index, event.getLegacyMatchId())
                .addValue("settledAt" + index, settledAt)
                .addValue("buyerId" + index, event.getBuyerId())
                .addValue("sellerId" + index, event.getSellerId())
                .addValue("buyerOrderId" + index, event.getBuyerOrderId())
                .addValue("sellerOrderId" + index, event.getSellerOrderId())
                .addValue("dealPrice" + index, event.getDealPrice())
                .addValue("quantity" + index, event.getQuantity())
                .addValue("originalLockedCurrency" + index, originalLockedCurrency)
                .addValue("refundCurrency" + index, refundCurrency)
                .addValue("dealCurrency" + index, dealCurrency);
    }

    public record SettlementOutcome(
            int insertedSettlements,
            int updatedBuyers,
            int updatedSellers,
            int originalLockedCurrency,
            int refundCurrency,
            int dealCurrency,
            LocalDateTime settledAt) {

        boolean duplicate() {
            return insertedSettlements == 0;
        }

        boolean completed() {
            return insertedSettlements == 1
                    && updatedBuyers == 1
                    && updatedSellers == 1;
        }
    }

    public record BatchSettlementOutcome(
            int requestedSettlements,
            int existingSettlements,
            int insertedSettlements,
            int updatedBuyers,
            int updatedSellers) {

        boolean hasExistingSettlements() {
            return existingSettlements > 0;
        }

        boolean completed() {
            return requestedSettlements > 0
                    && existingSettlements == 0
                    && insertedSettlements == requestedSettlements
                    && updatedBuyers == requestedSettlements
                    && updatedSellers == requestedSettlements;
        }
    }
}

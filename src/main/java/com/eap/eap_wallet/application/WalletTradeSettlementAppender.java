package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletTradeSettlementAppender {

    private static final String APPEND_BATCH_SQL = """
            WITH input(trade_id, legacy_match_id, settled_at,
                       buyer_id, seller_id, buyer_order_id, seller_order_id,
                       deal_price, quantity, original_locked_currency, refund_currency,
                       deal_currency) AS (
                SELECT *
                FROM unnest(?::varchar[], ?::integer[], ?::timestamp[],
                            ?::uuid[], ?::uuid[], ?::uuid[], ?::uuid[],
                            ?::integer[], ?::integer[], ?::integer[], ?::integer[],
                            ?::integer[])
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
            """;

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

        long cteStartedAt = System.nanoTime();
        try {
            return jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<BatchSettlementOutcome>) connection -> {
                Array tradeIds = null;
                Array legacyMatchIds = null;
                Array settledAts = null;
                Array buyerIds = null;
                Array sellerIds = null;
                Array buyerOrderIds = null;
                Array sellerOrderIds = null;
                Array dealPrices = null;
                Array quantities = null;
                Array originalLockedCurrencies = null;
                Array refundCurrencies = null;
                Array dealCurrencies = null;
                try (PreparedStatement statement = connection.prepareStatement(APPEND_BATCH_SQL)) {
                    BatchSettlementArrays arrays = batchSettlementArrays(events);
                    tradeIds = connection.createArrayOf("varchar", arrays.tradeIds());
                    legacyMatchIds = connection.createArrayOf("integer", arrays.legacyMatchIds());
                    settledAts = connection.createArrayOf("timestamp", arrays.settledAts());
                    buyerIds = connection.createArrayOf("uuid", arrays.buyerIds());
                    sellerIds = connection.createArrayOf("uuid", arrays.sellerIds());
                    buyerOrderIds = connection.createArrayOf("uuid", arrays.buyerOrderIds());
                    sellerOrderIds = connection.createArrayOf("uuid", arrays.sellerOrderIds());
                    dealPrices = connection.createArrayOf("integer", arrays.dealPrices());
                    quantities = connection.createArrayOf("integer", arrays.quantities());
                    originalLockedCurrencies = connection.createArrayOf("integer", arrays.originalLockedCurrencies());
                    refundCurrencies = connection.createArrayOf("integer", arrays.refundCurrencies());
                    dealCurrencies = connection.createArrayOf("integer", arrays.dealCurrencies());

                    statement.setArray(1, tradeIds);
                    statement.setArray(2, legacyMatchIds);
                    statement.setArray(3, settledAts);
                    statement.setArray(4, buyerIds);
                    statement.setArray(5, sellerIds);
                    statement.setArray(6, buyerOrderIds);
                    statement.setArray(7, sellerOrderIds);
                    statement.setArray(8, dealPrices);
                    statement.setArray(9, quantities);
                    statement.setArray(10, originalLockedCurrencies);
                    statement.setArray(11, refundCurrencies);
                    statement.setArray(12, dealCurrencies);

                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("Wallet trade settlement batch did not return an outcome");
                        }
                        return new BatchSettlementOutcome(
                                events.size(),
                                rs.getInt("existing_settlements"),
                                rs.getInt("inserted_settlements"),
                                rs.getInt("updated_buyers"),
                                rs.getInt("updated_sellers"));
                    }
                } finally {
                    freeQuietly(tradeIds);
                    freeQuietly(legacyMatchIds);
                    freeQuietly(settledAts);
                    freeQuietly(buyerIds);
                    freeQuietly(sellerIds);
                    freeQuietly(buyerOrderIds);
                    freeQuietly(sellerOrderIds);
                    freeQuietly(dealPrices);
                    freeQuietly(quantities);
                    freeQuietly(originalLockedCurrencies);
                    freeQuietly(refundCurrencies);
                    freeQuietly(dealCurrencies);
                }
            });
        } finally {
            walletMetrics.recordTradeSettlementCte(Duration.ofNanos(System.nanoTime() - cteStartedAt));
        }
    }

    private BatchSettlementArrays batchSettlementArrays(List<TradeExecutedEvent> events) {
        int size = events.size();
        String[] tradeIds = new String[size];
        Integer[] legacyMatchIds = new Integer[size];
        Timestamp[] settledAts = new Timestamp[size];
        UUID[] buyerIds = new UUID[size];
        UUID[] sellerIds = new UUID[size];
        UUID[] buyerOrderIds = new UUID[size];
        UUID[] sellerOrderIds = new UUID[size];
        Integer[] dealPrices = new Integer[size];
        Integer[] quantities = new Integer[size];
        Integer[] originalLockedCurrencies = new Integer[size];
        Integer[] refundCurrencies = new Integer[size];
        Integer[] dealCurrencies = new Integer[size];

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < size; i++) {
            TradeExecutedEvent event = events.get(i);
            int dealCurrency = event.getDealPrice() * event.getQuantity();
            int originalLockedCurrency = event.getOriginBuyerPrice() * event.getQuantity();
            int refundCurrency = originalLockedCurrency - dealCurrency;
            LocalDateTime settledAt = event.getOccurredAt() == null ? now : event.getOccurredAt();

            tradeIds[i] = event.getTradeId();
            legacyMatchIds[i] = event.getLegacyMatchId();
            settledAts[i] = Timestamp.valueOf(settledAt);
            buyerIds[i] = event.getBuyerId();
            sellerIds[i] = event.getSellerId();
            buyerOrderIds[i] = event.getBuyerOrderId();
            sellerOrderIds[i] = event.getSellerOrderId();
            dealPrices[i] = event.getDealPrice();
            quantities[i] = event.getQuantity();
            originalLockedCurrencies[i] = originalLockedCurrency;
            refundCurrencies[i] = refundCurrency;
            dealCurrencies[i] = dealCurrency;
        }
        return new BatchSettlementArrays(
                tradeIds,
                legacyMatchIds,
                settledAts,
                buyerIds,
                sellerIds,
                buyerOrderIds,
                sellerOrderIds,
                dealPrices,
                quantities,
                originalLockedCurrencies,
                refundCurrencies,
                dealCurrencies);
    }

    private void freeQuietly(Array array) {
        if (array == null) {
            return;
        }
        try {
            array.free();
        } catch (Exception ignored) {
        }
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

    private record BatchSettlementArrays(
            String[] tradeIds,
            Integer[] legacyMatchIds,
            Timestamp[] settledAts,
            UUID[] buyerIds,
            UUID[] sellerIds,
            UUID[] buyerOrderIds,
            UUID[] sellerOrderIds,
            Integer[] dealPrices,
            Integer[] quantities,
            Integer[] originalLockedCurrencies,
            Integer[] refundCurrencies,
            Integer[] dealCurrencies) {
    }
}

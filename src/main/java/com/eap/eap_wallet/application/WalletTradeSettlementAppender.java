package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

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
        int[] existingSettlements = {0};
        int[] lockedWallets = {0};
        int[] updatedBuyers = {0};
        int[] updatedSellers = {0};
        long cteStartedAt = System.nanoTime();
        try {
            jdbcTemplate.query("""
                WITH locked_wallets AS MATERIALIZED (
                    SELECT user_id
                    FROM wallet_service.wallets
                    WHERE user_id IN (:buyerId, :sellerId)
                    ORDER BY user_id
                    FOR UPDATE
                ),
                existing_settlement AS MATERIALIZED (
                    SELECT trade_id
                    FROM wallet_service.trade_settlements
                    WHERE trade_id = :tradeId
                ),
                settlement AS (
                    INSERT INTO wallet_service.trade_settlements
                        (trade_id, legacy_match_id, settled_at)
                    SELECT :tradeId, :legacyMatchId, :settledAt
                    WHERE (SELECT COUNT(*) FROM locked_wallets) = 2
                      AND NOT EXISTS (SELECT 1 FROM existing_settlement)
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
                      AND EXISTS (
                          SELECT 1 FROM locked_wallets WHERE user_id = :buyerId)
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
                      AND EXISTS (
                          SELECT 1 FROM locked_wallets WHERE user_id = :sellerId)
                      AND EXISTS (SELECT 1 FROM settlement)
                      AND locked_amount >= :quantity
                    RETURNING 1
                )
                SELECT
                    (SELECT COUNT(*) FROM locked_wallets) AS locked_wallets,
                    (SELECT COUNT(*) FROM existing_settlement) AS existing_settlements,
                    (SELECT COUNT(*) FROM settlement) AS inserted_settlements,
                    (SELECT COUNT(*) FROM buyer_update) AS updated_buyers,
                    (SELECT COUNT(*) FROM seller_update) AS updated_sellers
                """, new MapSqlParameterSource()
                .addValue("tradeId", event.getTradeId())
                .addValue("legacyMatchId", event.getLegacyMatchId())
                .addValue("settledAt", settledAt)
                .addValue("buyerId", event.getBuyerId())
                .addValue("sellerId", event.getSellerId())
                .addValue("originalLockedCurrency", originalLockedCurrency)
                .addValue("refundCurrency", refundCurrency)
                .addValue("dealCurrency", dealCurrency)
                .addValue("quantity", event.getQuantity()), rs -> {
                    lockedWallets[0] = rs.getInt("locked_wallets");
                    existingSettlements[0] = rs.getInt("existing_settlements");
                    insertedSettlements[0] = rs.getInt("inserted_settlements");
                    updatedBuyers[0] = rs.getInt("updated_buyers");
                    updatedSellers[0] = rs.getInt("updated_sellers");
                });
        } finally {
            walletMetrics.recordTradeSettlementCte(Duration.ofNanos(System.nanoTime() - cteStartedAt));
        }

        SettlementOutcome outcome = new SettlementOutcome(
                lockedWallets[0],
                existingSettlements[0],
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
            throw new IllegalStateException("Wallet trade settlement did not persist settlement and update both wallets: tradeId="
                    + event.getTradeId()
                    + ", lockedWallets=" + outcome.lockedWallets()
                    + ", existingSettlements=" + outcome.existingSettlements()
                    + ", insertedSettlements=" + outcome.insertedSettlements()
                    + ", updatedBuyers=" + outcome.updatedBuyers()
                    + ", updatedSellers=" + outcome.updatedSellers());
        }
        return outcome;
    }

    public record SettlementOutcome(
            int lockedWallets,
            int existingSettlements,
            int insertedSettlements,
            int updatedBuyers,
            int updatedSellers,
            int originalLockedCurrency,
            int refundCurrency,
            int dealCurrency,
            LocalDateTime settledAt) {

        boolean duplicate() {
            return existingSettlements == 1 && insertedSettlements == 0;
        }

        boolean completed() {
            return lockedWallets == 2
                    && existingSettlements == 0
                    && insertedSettlements == 1
                    && updatedBuyers == 1
                    && updatedSellers == 1;
        }
    }

}

package com.eap.eap_wallet.application;

import com.eap.common.event.OrderCancellationResultEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WalletOrderCancellationAppender {

    private final NamedParameterJdbcTemplate jdbc;

    public CancellationOutcome release(OrderCancellationResultEvent event) {
        validate(event);
        int releaseCurrency = "BUY".equals(event.getOrderType())
                ? Math.multiplyExact(event.getLimitPrice(), event.getCancelledAmount())
                : 0;

        CancellationOutcome outcome = jdbc.queryForObject("""
                WITH locked_wallet AS MATERIALIZED (
                    SELECT user_id
                    FROM wallet_service.wallets
                    WHERE user_id = :userId
                    FOR UPDATE
                ),
                existing_application AS MATERIALIZED (
                    SELECT cancellation_id, order_id, user_id, side, limit_price, cancelled_quantity
                    FROM wallet_service.order_cancellation_applications
                    WHERE cancellation_id = :cancellationId OR order_id = :orderId
                ),
                matching_application AS MATERIALIZED (
                    SELECT cancellation_id
                    FROM existing_application
                    WHERE cancellation_id = :cancellationId
                      AND order_id = :orderId
                      AND user_id = :userId
                      AND side = :side
                      AND limit_price = :limitPrice
                      AND cancelled_quantity = :cancelledAmount
                ),
                application_inserted AS (
                    INSERT INTO wallet_service.order_cancellation_applications
                        (cancellation_id, order_id, user_id, side, limit_price,
                         cancelled_quantity, applied_at)
                    SELECT :cancellationId, :orderId, :userId, :side, :limitPrice,
                           :cancelledAmount, CURRENT_TIMESTAMP
                    FROM locked_wallet
                    WHERE NOT EXISTS (SELECT 1 FROM existing_application)
                    ON CONFLICT DO NOTHING
                    RETURNING cancellation_id
                ),
                wallet_update AS (
                    UPDATE wallet_service.wallets wallet
                    SET available_currency = CASE WHEN :side = 'BUY'
                            THEN wallet.available_currency + :releaseCurrency
                            ELSE wallet.available_currency END,
                        locked_currency = CASE WHEN :side = 'BUY'
                            THEN wallet.locked_currency - :releaseCurrency
                            ELSE wallet.locked_currency END,
                        available_amount = CASE WHEN :side = 'SELL'
                            THEN wallet.available_amount + :cancelledAmount
                            ELSE wallet.available_amount END,
                        locked_amount = CASE WHEN :side = 'SELL'
                            THEN wallet.locked_amount - :cancelledAmount
                            ELSE wallet.locked_amount END,
                        version = version + 1,
                        update_time = CURRENT_TIMESTAMP
                    WHERE wallet.user_id = :userId
                      AND EXISTS (SELECT 1 FROM application_inserted)
                      AND (:side <> 'BUY' OR wallet.locked_currency >= :releaseCurrency)
                      AND (:side <> 'SELL' OR wallet.locked_amount >= :cancelledAmount)
                    RETURNING wallet.user_id
                )
                SELECT
                    (SELECT COUNT(*) FROM existing_application) AS existing_applications,
                    (SELECT COUNT(*) FROM matching_application) AS matching_applications,
                    (SELECT COUNT(*) FROM application_inserted) AS inserted_applications,
                    (SELECT COUNT(*) FROM wallet_update) AS wallet_updates
                """, params(event, releaseCurrency), (rs, rowNum) -> new CancellationOutcome(
                rs.getInt("existing_applications"),
                rs.getInt("matching_applications"),
                rs.getInt("inserted_applications"),
                rs.getInt("wallet_updates")));
        if (outcome.duplicate()) {
            return outcome;
        }
        if (!outcome.completed()) {
            if (outcome.existingApplications() > 0) {
                throw new WalletMessageIdentityConflictException(
                        "Wallet cancellation identity conflicts with an existing application: cancellationId="
                                + event.getCancellationId() + ", orderId=" + event.getOrderId()
                                + ", outcome=" + outcome);
            }
            if (outcome.insertedApplications() == 1 && outcome.walletUpdates() == 0) {
                throw new WalletAssetConsistencyException(
                        "Wallet cancellation release exceeds locked assets: cancellationId="
                                + event.getCancellationId() + ", orderId=" + event.getOrderId()
                                + ", side=" + event.getOrderType()
                                + ", cancelledAmount=" + event.getCancelledAmount()
                                + ", outcome=" + outcome);
            }
            throw new WalletMessageIdentityConflictException(
                    "Wallet cancellation cannot find the expected wallet/reservation identity: cancellationId="
                            + event.getCancellationId() + ", orderId=" + event.getOrderId()
                            + ", outcome=" + outcome);
        }
        return outcome;
    }

    private MapSqlParameterSource params(OrderCancellationResultEvent event, int releaseCurrency) {
        return new MapSqlParameterSource()
                .addValue("cancellationId", event.getCancellationId())
                .addValue("orderId", event.getOrderId())
                .addValue("userId", event.getUserId())
                .addValue("side", event.getOrderType())
                .addValue("limitPrice", event.getLimitPrice())
                .addValue("cancelledAmount", event.getCancelledAmount())
                .addValue("releaseCurrency", releaseCurrency);
    }

    private void validate(OrderCancellationResultEvent event) {
        if (event == null || !event.cancelled()) {
            throw new IllegalArgumentException("Wallet cancellation release requires CANCELLED outcome");
        }
        if (event.getCancellationId() == null || event.getOrderId() == null || event.getUserId() == null) {
            throw new IllegalArgumentException("Wallet cancellation release identifiers are required");
        }
        if (!"BUY".equals(event.getOrderType()) && !"SELL".equals(event.getOrderType())) {
            throw new IllegalArgumentException("Wallet cancellation release side must be BUY or SELL");
        }
        if (event.getLimitPrice() == null || event.getLimitPrice() <= 0
                || event.getCancelledAmount() == null || event.getCancelledAmount() <= 0) {
            throw new IllegalArgumentException("Wallet cancellation release price and amount must be positive");
        }
    }

    public record CancellationOutcome(
            int existingApplications,
            int matchingApplications,
            int insertedApplications,
            int walletUpdates) {

        public boolean duplicate() {
            return existingApplications == 1
                    && matchingApplications == 1
                    && insertedApplications == 0
                    && walletUpdates == 0;
        }

        public boolean completed() {
            return existingApplications == 0
                    && matchingApplications == 0
                    && insertedApplications == 1
                    && walletUpdates == 1;
        }
    }
}

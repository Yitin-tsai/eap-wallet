package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trade_settlements", schema = "wallet_service")
@Getter
@NoArgsConstructor
public class TradeSettlementEntity {

    @Id
    @Column(name = "trade_id", nullable = false, length = 80)
    private String tradeId;

    @Column(name = "legacy_match_id")
    private Integer legacyMatchId;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt = LocalDateTime.now();

    @Column(name = "buyer_id")
    private UUID buyerId;

    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "buyer_order_id")
    private UUID buyerOrderId;

    @Column(name = "seller_order_id")
    private UUID sellerOrderId;

    @Column(name = "deal_price")
    private Integer dealPrice;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "buyer_locked_currency")
    private Integer buyerLockedCurrency;

    @Column(name = "buyer_refund_currency")
    private Integer buyerRefundCurrency;

    @Column(name = "seller_received_currency")
    private Integer sellerReceivedCurrency;

    @Column(name = "event_status", nullable = false, length = 10)
    private String eventStatus = "SENT";

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public TradeSettlementEntity(String tradeId, Integer legacyMatchId, LocalDateTime settledAt) {
        this.tradeId = tradeId;
        this.legacyMatchId = legacyMatchId;
        this.settledAt = settledAt == null ? LocalDateTime.now() : settledAt;
    }
}

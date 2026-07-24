package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(name = "inserted_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime insertedAt;

    public TradeSettlementEntity(String tradeId, Integer legacyMatchId, LocalDateTime settledAt) {
        this.tradeId = tradeId;
        this.legacyMatchId = legacyMatchId;
        this.settledAt = settledAt == null ? LocalDateTime.now() : settledAt;
    }
}

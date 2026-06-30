package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false, length = 80, unique = true)
    private String tradeId;

    @Column(name = "legacy_match_id")
    private Integer legacyMatchId;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt = LocalDateTime.now();

    public TradeSettlementEntity(String tradeId, Integer legacyMatchId, LocalDateTime settledAt) {
        this.tradeId = tradeId;
        this.legacyMatchId = legacyMatchId;
        this.settledAt = settledAt == null ? LocalDateTime.now() : settledAt;
    }
}

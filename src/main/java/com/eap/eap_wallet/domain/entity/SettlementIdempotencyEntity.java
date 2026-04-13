package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlement_idempotency", schema = "wallet_service",
       uniqueConstraints = @UniqueConstraint(name = "uk_settlement_idempotency",
           columnNames = {"auction_id", "user_id", "side"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementIdempotencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false, length = 20)
    private String auctionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "side", nullable = false, length = 4)
    private String side;

    @Column(name = "settled_at", nullable = false)
    @Builder.Default
    private LocalDateTime settledAt = LocalDateTime.now();
}

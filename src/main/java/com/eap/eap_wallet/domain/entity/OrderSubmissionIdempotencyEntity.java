package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "order_submission_idempotency", schema = "wallet_service",
       uniqueConstraints = @UniqueConstraint(name = "uk_order_submission_idempotency",
           columnNames = {"order_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSubmissionIdempotencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "recorded_at", nullable = false)
    @Builder.Default
    private LocalDateTime recordedAt = LocalDateTime.now();
}

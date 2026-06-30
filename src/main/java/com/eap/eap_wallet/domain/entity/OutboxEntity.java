package com.eap.eap_wallet.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox", schema = "wallet_service")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 50)
    private String routingKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 10)
    private String status = "PENDING";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt = LocalDateTime.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public OutboxEntity(String eventType, String routingKey, String payload) {
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.payload = payload;
    }
}

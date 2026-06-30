package com.eap.eap_wallet.configuration.repository;

import com.eap.eap_wallet.domain.entity.OrderSubmissionIdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrderSubmissionIdempotencyRepository extends JpaRepository<OrderSubmissionIdempotencyEntity, Long> {

    boolean existsByOrderId(UUID orderId);

    @Modifying
    @Query(value = """
            INSERT INTO wallet_service.order_submission_idempotency(order_id, user_id, recorded_at)
            VALUES (:orderId, :userId, CURRENT_TIMESTAMP)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int claimOrderSubmission(@Param("orderId") UUID orderId, @Param("userId") UUID userId);
}

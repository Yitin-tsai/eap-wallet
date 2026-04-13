package com.eap.eap_wallet.configuration.repository;

import com.eap.eap_wallet.domain.entity.SettlementIdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementIdempotencyRepository extends JpaRepository<SettlementIdempotencyEntity, Long> {

    boolean existsByAuctionIdAndUserIdAndSide(String auctionId, UUID userId, String side);
}

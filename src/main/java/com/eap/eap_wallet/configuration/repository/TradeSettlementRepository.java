package com.eap.eap_wallet.configuration.repository;

import com.eap.eap_wallet.domain.entity.TradeSettlementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeSettlementRepository extends JpaRepository<TradeSettlementEntity, Long> {
    boolean existsByTradeId(String tradeId);
}

package com.eap.eap_wallet.configuration.repository;

import com.eap.eap_wallet.domain.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    List<OutboxEntity> findByStatusOrderByCreatedAtAsc(String status);

    void deleteByStatusAndCreatedAtBefore(String status, LocalDateTime before);
}

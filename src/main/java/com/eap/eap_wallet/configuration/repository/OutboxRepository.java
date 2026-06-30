package com.eap.eap_wallet.configuration.repository;

import com.eap.eap_wallet.domain.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    List<OutboxEntity> findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
            String status,
            LocalDateTime nextRetryAt,
            Pageable pageable
    );

    Optional<OutboxEntity> findFirstByStatusOrderByCreatedAtAsc(String status);

    List<OutboxEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    long countByStatus(String status);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            update OutboxEntity o
               set o.status = 'SENT',
                   o.nextRetryAt = null,
                   o.lastError = null,
                   o.updatedAt = :updatedAt
             where o.id in :ids
               and o.status = 'PENDING'
            """)
    int markPendingAsSent(
            @Param("ids") List<Long> ids,
            @Param("updatedAt") LocalDateTime updatedAt);

    void deleteByStatusAndCreatedAtBefore(String status, LocalDateTime before);
}

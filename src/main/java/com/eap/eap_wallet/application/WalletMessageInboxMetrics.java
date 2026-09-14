package com.eap.eap_wallet.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class WalletMessageInboxMetrics {

    private static final List<String> STATUSES = List.of(
            "PENDING", "IN_PROGRESS", "APPLIED", "FAILED_RETRYABLE", "FAILED_PERMANENT");

    private final WalletMessageInbox inbox;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    public WalletMessageInboxMetrics(MeterRegistry registry, WalletMessageInbox inbox) {
        this.inbox = inbox;
        for (String status : STATUSES) {
            Gauge.builder("eap_wallet_inbox_messages", this,
                            source -> source.count(status))
                    .description("Current Wallet durable inbox rows by processing status")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder("eap_wallet_inbox_identity_conflicts", this,
                        source -> source.snapshot.get().identityConflicts())
                .description("Current Wallet durable inbox rows with an identity conflict")
                .register(registry);
        Gauge.builder("eap_wallet_inbox_oldest_unresolved_age_seconds", this,
                        source -> source.snapshot.get().oldestUnresolvedAgeSeconds())
                .description("Age in seconds of the oldest Wallet inbox row not yet applied")
                .register(registry);
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    @Scheduled(
            fixedDelayString = "${eap.wallet.inbox-metrics.refresh-interval-ms:5000}",
            initialDelayString = "${eap.wallet.inbox-metrics.refresh-interval-ms:5000}")
    void refresh() {
        try {
            snapshot.set(new Snapshot(
                    inbox.countByStatus(),
                    inbox.countIdentityConflicts(),
                    inbox.oldestUnresolvedAgeSeconds()));
            if (refreshFailureLogged.getAndSet(false)) {
                log.info("Wallet inbox metrics refresh recovered");
            }
        } catch (RuntimeException failure) {
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Could not refresh Wallet inbox metrics; retaining the previous snapshot", failure);
            }
        }
    }

    private long count(String status) {
        return snapshot.get().counts().getOrDefault(status, 0L);
    }

    private record Snapshot(
            Map<String, Long> counts,
            long identityConflicts,
            long oldestUnresolvedAgeSeconds) {

        private static Snapshot empty() {
            return new Snapshot(Map.of(), 0L, 0L);
        }
    }
}

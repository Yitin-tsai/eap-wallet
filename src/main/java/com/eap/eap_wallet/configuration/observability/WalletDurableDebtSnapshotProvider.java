package com.eap.eap_wallet.configuration.observability;

import com.eap.common.observability.DurableDebtSnapshot;
import com.eap.common.observability.DurableDebtSnapshotCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.eap.eap_wallet.configuration.config.WalletSchedulerConfig.DURABLE_DEBT_SCHEDULER;

@Component
@Slf4j
public class WalletDurableDebtSnapshotProvider {

    public static final List<String> WORK = List.of(
            "order_submission_inbox",
            "cancellation_result_inbox",
            "trade_execution_inbox",
            "event_outbox");

    private static final String SQL = """
            SELECT work, total_count, retry_count, terminal_count,
                   oldest_unresolved_age_seconds
            FROM (
                SELECT CASE message_type
                           WHEN 'ORDER_SUBMITTED' THEN 'order_submission_inbox'
                           WHEN 'ORDER_CANCELLATION_RESULT' THEN 'cancellation_result_inbox'
                           WHEN 'TRADE_EXECUTED' THEN 'trade_execution_inbox'
                       END AS work,
                       count(*) AS total_count,
                       count(*) FILTER (WHERE status = 'FAILED_RETRYABLE'
                           OR (status = 'IN_PROGRESS' AND error_type IS NOT NULL)) AS retry_count,
                       count(*) FILTER (WHERE status = 'FAILED_PERMANENT'
                                            OR conflict_detected_at IS NOT NULL) AS terminal_count,
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - CASE
                               WHEN conflict_detected_at IS NOT NULL
                                   THEN LEAST(received_at, conflict_detected_at)
                               ELSE received_at END)))::bigint)), 0) AS oldest_unresolved_age_seconds
                FROM wallet_service.message_inbox
                WHERE status <> 'APPLIED' OR conflict_detected_at IS NOT NULL
                GROUP BY message_type
                UNION ALL
                SELECT 'event_outbox', count(*),
                       count(*) FILTER (WHERE status IN ('PENDING', 'IN_FLIGHT') AND attempt_count > 0),
                       count(*) FILTER (WHERE status = 'FAILED'),
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - created_at)))::bigint)), 0)
                FROM wallet_service.outbox
                WHERE status <> 'SENT'
            ) debt
            WHERE work IS NOT NULL
            """;

    private final JdbcTemplate jdbc;
    private final DurableDebtSnapshotCache cache =
            new DurableDebtSnapshotCache("eap-wallet", WORK);
    private final Counter refreshFailures;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    public WalletDurableDebtSnapshotProvider(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.refreshFailures = Counter.builder("eap_durable_debt_refresh_failures_total")
                .description("Durable-debt snapshot refresh failures")
                .tag("service", "eap-wallet")
                .register(registry);
        registerGauges(registry);
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    @Scheduled(
            fixedDelayString = "${eap.durable-debt.refresh-interval-ms:5000}",
            initialDelayString = "${eap.durable-debt.refresh-interval-ms:5000}",
            scheduler = DURABLE_DEBT_SCHEDULER)
    void refresh() {
        try {
            cache.recordSuccess(jdbc.query(SQL, (rs, rowNum) ->
                    new DurableDebtSnapshot.ComponentDebt(
                            rs.getString("work"),
                            rs.getLong("total_count"),
                            rs.getLong("retry_count"),
                            rs.getLong("terminal_count"),
                            rs.getLong("oldest_unresolved_age_seconds"))));
            if (refreshFailureLogged.getAndSet(false)) {
                log.info("Wallet durable-debt snapshot refresh recovered");
            }
        } catch (RuntimeException failure) {
            cache.recordFailure();
            refreshFailures.increment();
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Could not refresh Wallet durable-debt snapshot; retaining last successful values", failure);
            }
        }
    }

    public DurableDebtSnapshot snapshot() {
        return cache.snapshot();
    }

    private void registerGauges(MeterRegistry registry) {
        for (String work : WORK) {
            registerCountGauge(registry, work, "total");
            registerCountGauge(registry, work, "retry");
            registerCountGauge(registry, work, "terminal");
            Gauge.builder("eap_durable_debt_oldest_age_seconds", cache,
                            source -> source.component(work).oldestUnresolvedAgeSeconds())
                    .description("Age of the oldest unresolved durable work item")
                    .tags("service", "eap-wallet", "work", work)
                    .register(registry);
        }
        Gauge.builder("eap_durable_debt_observation_success", cache,
                        source -> source.snapshot().observationSuccess() ? 1 : 0)
                .description("Whether the latest durable-debt observation succeeded")
                .tag("service", "eap-wallet")
                .register(registry);
        Gauge.builder("eap_durable_debt_snapshot_age_seconds", cache,
                        source -> source.snapshot().snapshotAgeSeconds())
                .description("Age of the last successful durable-debt snapshot")
                .tag("service", "eap-wallet")
                .register(registry);
        Gauge.builder("eap_durable_debt_contract_version", cache,
                        source -> source.snapshot().contractVersion())
                .description("Durable-debt snapshot contract version")
                .tag("service", "eap-wallet")
                .register(registry);
    }

    private void registerCountGauge(MeterRegistry registry, String work, String debtClass) {
        Gauge.builder("eap_durable_debt_items", cache, source -> {
                    DurableDebtSnapshot.ComponentDebt debt = source.component(work);
                    return switch (debtClass) {
                        case "total" -> debt.totalCount();
                        case "retry" -> debt.retryCount();
                        case "terminal" -> debt.terminalCount();
                        default -> throw new IllegalStateException("unsupported durable-debt class " + debtClass);
                    };
                })
                .description("Current durable work items by semantic debt class")
                .tags("service", "eap-wallet", "work", work, "class", debtClass)
                .register(registry);
    }
}

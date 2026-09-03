package com.eap.eap_wallet.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WalletMessageInboxMetrics {

    public WalletMessageInboxMetrics(MeterRegistry registry, WalletMessageInbox inbox) {
        for (String status : List.of(
                "PENDING", "IN_PROGRESS",
                "APPLIED", "FAILED_RETRYABLE", "FAILED_PERMANENT")) {
            Gauge.builder("eap_wallet_inbox_messages", inbox,
                            source -> source.countByStatus().getOrDefault(status, 0L))
                    .description("Current Wallet durable inbox rows by processing status")
                    .tag("status", status)
                    .register(registry);
        }
    }
}

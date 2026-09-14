package com.eap.eap_wallet.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletMessageInboxMetricsTest {

    @Test
    void refresh_shouldPublishDurableDebtAndRetainLastSnapshotAfterDatabaseFailure() {
        WalletMessageInbox inbox = mock(WalletMessageInbox.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(inbox.countByStatus())
                .thenReturn(Map.of("PENDING", 3L, "FAILED_PERMANENT", 2L))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(inbox.countIdentityConflicts()).thenReturn(4L);
        when(inbox.oldestUnresolvedAgeSeconds()).thenReturn(17L);
        WalletMessageInboxMetrics metrics = new WalletMessageInboxMetrics(registry, inbox);

        metrics.initialize();

        assertThat(registry.get("eap_wallet_inbox_messages")
                .tag("status", "PENDING").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("eap_wallet_inbox_messages")
                .tag("status", "FAILED_PERMANENT").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("eap_wallet_inbox_identity_conflicts").gauge().value())
                .isEqualTo(4.0);
        assertThat(registry.get("eap_wallet_inbox_oldest_unresolved_age_seconds").gauge().value())
                .isEqualTo(17.0);

        metrics.refresh();

        assertThat(registry.get("eap_wallet_inbox_messages")
                .tag("status", "PENDING").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("eap_wallet_inbox_oldest_unresolved_age_seconds").gauge().value())
                .isEqualTo(17.0);
    }
}

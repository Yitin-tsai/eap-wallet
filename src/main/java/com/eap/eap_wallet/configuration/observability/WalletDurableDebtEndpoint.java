package com.eap.eap_wallet.configuration.observability;

import com.eap.common.observability.DurableDebtSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "durableDebt")
@RequiredArgsConstructor
public class WalletDurableDebtEndpoint {

    private final WalletDurableDebtSnapshotProvider provider;

    @ReadOperation
    public DurableDebtSnapshot snapshot() {
        return provider.snapshot();
    }
}

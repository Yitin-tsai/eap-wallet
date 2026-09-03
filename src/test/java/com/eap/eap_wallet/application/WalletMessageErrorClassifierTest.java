package com.eap.eap_wallet.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WalletMessageErrorClassifierTest {

    private final WalletMessageErrorClassifier classifier = new WalletMessageErrorClassifier();

    @Test
    void assetConsistencyConflict_shouldBePermanent() {
        WalletMessageErrorClassifier.Classification result = classifier.classify(
                new WalletAssetConsistencyException("release exceeds locked asset"));

        assertFalse(result.retryable());
        assertEquals("PERMANENT_ASSET_INVARIANT", result.errorType());
    }
}

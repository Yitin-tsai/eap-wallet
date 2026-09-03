package com.eap.eap_wallet.application;

/**
 * A durable Wallet fact conflicts with the asset state that should already
 * exist. Retrying the same message cannot restore the violated invariant.
 */
public class WalletAssetConsistencyException extends RuntimeException {

    public WalletAssetConsistencyException(String message) {
        super(message);
    }
}

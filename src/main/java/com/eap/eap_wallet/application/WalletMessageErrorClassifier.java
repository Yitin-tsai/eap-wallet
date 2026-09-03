package com.eap.eap_wallet.application;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class WalletMessageErrorClassifier {

    public Classification classify(Exception failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof WalletAssetConsistencyException) {
                return new Classification(false, "PERMANENT_ASSET_INVARIANT");
            }
            if (current instanceof WalletMessageIdentityConflictException) {
                return new Classification(false, "PERMANENT_IDENTITY_CONFLICT");
            }
            if (current instanceof DataIntegrityViolationException) {
                return new Classification(false, "PERMANENT_DATA_INTEGRITY");
            }
            if (current instanceof DataAccessException) {
                return new Classification(true, "TRANSIENT_DATABASE");
            }
            if (current instanceof IllegalArgumentException || current instanceof ArithmeticException) {
                return new Classification(false, "PERMANENT_INVALID_EVENT");
            }
            current = current.getCause();
        }
        return new Classification(true, "UNKNOWN_RETRYABLE");
    }

    public record Classification(boolean retryable, String errorType) {
    }
}

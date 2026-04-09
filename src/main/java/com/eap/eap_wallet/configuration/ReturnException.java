package com.eap.eap_wallet.configuration;

public class ReturnException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;

    public ReturnException(String message) {
        super(message);
    }

    public ReturnException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReturnException(Throwable cause) {
        super(cause);
    }

}

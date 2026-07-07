package com.picsou.exception;

public class FinaryServiceUnavailableException extends RuntimeException {
    public FinaryServiceUnavailableException(String message) {
        super(message);
    }

    public FinaryServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

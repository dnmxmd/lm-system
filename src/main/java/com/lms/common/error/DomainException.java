package com.lms.common.error;

/**
 * Base type for errors that are part of the business vocabulary.
 * The {@code code} is stable and machine-readable; clients branch on it, not on the message.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

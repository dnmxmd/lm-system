package com.lms.common.error;

/** A business rule rejected otherwise well-formed input. Maps to HTTP 422. */
public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super("VALIDATION_FAILED", message);
    }
}

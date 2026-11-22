package com.lms.common.error;

/** The request clashes with the current state of the system. Maps to HTTP 409. */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super("CONFLICT", message);
    }
}

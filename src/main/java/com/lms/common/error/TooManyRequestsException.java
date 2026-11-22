package com.lms.common.error;

/** The caller has used up their request budget. Maps to HTTP 429. */
public class TooManyRequestsException extends DomainException {

    public TooManyRequestsException(String message) {
        super("TOO_MANY_REQUESTS", message);
    }
}

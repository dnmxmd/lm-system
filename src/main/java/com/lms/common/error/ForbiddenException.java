package com.lms.common.error;

/** The caller is authenticated but not allowed to touch this resource. Maps to HTTP 403. */
public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super("FORBIDDEN", message);
    }
}

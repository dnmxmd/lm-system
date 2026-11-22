package com.lms.common.error;

/** The requested record does not exist. Maps to HTTP 404. */
public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message);
    }

    public static NotFoundException of(String type, Object id) {
        return new NotFoundException(type + " " + id + " was not found");
    }
}

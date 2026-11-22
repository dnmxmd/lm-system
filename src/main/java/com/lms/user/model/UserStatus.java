package com.lms.user.model;

public enum UserStatus {
    /** Can sign in and use the platform. */
    ACTIVE,
    /** Blocked by an administrator; credentials still exist but sign-in is refused. */
    SUSPENDED
}

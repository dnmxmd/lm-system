package com.lms.common.security;

public enum Role {
    STUDENT,
    INSTRUCTOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}

package com.lms.common.security;

import java.util.UUID;

/**
 * The caller, as proven by the access token. Controllers receive it through
 * {@code @AuthenticationPrincipal} and never re-read the token themselves.
 */
public record AuthenticatedUser(UUID id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean is(UUID userId) {
        return id.equals(userId);
    }
}

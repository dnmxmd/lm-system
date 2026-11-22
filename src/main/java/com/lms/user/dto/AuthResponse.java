package com.lms.user.dto;

import java.time.Instant;

public record AuthResponse(String accessToken, String tokenType, Instant expiresAt,
                           long expiresInSeconds, UserResponse user) {

    public static AuthResponse bearer(String accessToken, Instant expiresAt, long expiresInSeconds,
                                      UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresAt, expiresInSeconds, user);
    }
}

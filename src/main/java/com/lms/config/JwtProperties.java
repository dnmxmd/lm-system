package com.lms.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lms.security.jwt")
public record JwtProperties(@NotBlank String secret, String issuer, Duration accessTtl) {

    /** The value shipped in .env.example and application.yml. Fine locally, fatal in production. */
    public static final String PLACEHOLDER_SECRET = "change-me-in-production-this-is-at-least-32-bytes-long";

    public JwtProperties {
        issuer = issuer == null ? "lms" : issuer;
        accessTtl = accessTtl == null ? Duration.ofHours(1) : accessTtl;
    }

    public boolean isPlaceholder() {
        return PLACEHOLDER_SECRET.equals(secret);
    }
}

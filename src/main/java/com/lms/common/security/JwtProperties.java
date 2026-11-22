package com.lms.common.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lms.security.jwt")
public record JwtProperties(@NotBlank String secret, String issuer, Duration accessTtl) {

    public JwtProperties {
        issuer = issuer == null ? "lms" : issuer;
        accessTtl = accessTtl == null ? Duration.ofHours(1) : accessTtl;
    }
}

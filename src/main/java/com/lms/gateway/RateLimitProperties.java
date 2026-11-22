package com.lms.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "lms.rate-limit")
public record RateLimitProperties(boolean enabled, int requests, Duration window) {

    public RateLimitProperties {
        requests = requests <= 0 ? 100 : requests;
        window = window == null ? Duration.ofMinutes(1) : window;
    }
}

package com.lms.outbox.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param autoDelivery  whether the relay delivers on its own. Off lets an operator pause delivery,
 *                      and lets tests drive the relay a pass at a time instead of racing it.
 * @param batchSize     how many events one relay pass takes on
 * @param maxAttempts   attempts before an event is dead-lettered for somebody to look at
 * @param claimTimeout  how long a claimed event may sit before it is assumed its relay died
 * @param retention     how long delivered events are kept, for audit
 */
@ConfigurationProperties(prefix = "lms.outbox")
public record OutboxProperties(Boolean autoDelivery, int batchSize, int maxAttempts,
                               Duration claimTimeout, Duration retention) {

    public OutboxProperties {
        autoDelivery = autoDelivery == null || autoDelivery;
        batchSize = batchSize <= 0 ? 50 : batchSize;
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        claimTimeout = claimTimeout == null ? Duration.ofMinutes(5) : claimTimeout;
        retention = retention == null ? Duration.ofDays(30) : retention;
    }

    public boolean deliversAutomatically() {
        return Boolean.TRUE.equals(autoDelivery);
    }
}

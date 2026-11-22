package com.lms.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowSimulatedSettlement whether the confirm/fail/retry endpoints that stand in for a
 *                                 payment provider are exposed. Off unless explicitly enabled,
 *                                 because they let a learner settle their own charge.
 */
@ConfigurationProperties(prefix = "lms.payments")
public record PaymentProperties(boolean allowSimulatedSettlement) {
}

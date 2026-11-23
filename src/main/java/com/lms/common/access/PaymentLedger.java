package com.lms.common.access;

import java.util.UUID;

/**
 * Whether an enrolment has already been paid for.
 *
 * <p>In {@code common} for the same reason as {@link CourseAccess}: enrolment has to ask the
 * question and only payment can answer it, and payment already depends on enrolment. Declaring it
 * in either would point the two at each other.
 */
public interface PaymentLedger {

    /** True when a payment for this enrolment succeeded and has not since been refunded. */
    boolean hasSettledPayment(UUID enrollmentId);
}

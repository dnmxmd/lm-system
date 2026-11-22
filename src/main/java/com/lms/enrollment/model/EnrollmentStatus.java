package com.lms.enrollment.model;

public enum EnrollmentStatus {
    /** The course costs money and payment has not settled yet. No access to lessons. */
    PENDING_PAYMENT,
    /** The learner is in and can work through the lessons. */
    ACTIVE,
    /** Every lesson is done. */
    COMPLETED,
    /** Withdrawn by the learner, or refunded. */
    CANCELLED
}

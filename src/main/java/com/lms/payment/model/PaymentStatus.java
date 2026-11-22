package com.lms.payment.model;

public enum PaymentStatus {
    /** Raised for an enrolment that needs paying; no money has moved. */
    PENDING,
    /** The provider confirmed the charge. */
    SUCCEEDED,
    /** The provider declined it. */
    FAILED,
    /** Settled, then given back. */
    REFUNDED
}

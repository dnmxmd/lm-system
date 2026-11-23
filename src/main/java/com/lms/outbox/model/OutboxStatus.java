package com.lms.outbox.model;

public enum OutboxStatus {
    /** Written, not yet delivered. Due when {@code nextAttemptAt} has passed. */
    PENDING,
    /** Taken by a relay pass. Guards against two instances delivering the same row. */
    CLAIMED,
    /** Delivered to every consumer that wanted it. */
    PUBLISHED,
    /** Out of attempts. Needs somebody to look at it. */
    FAILED
}

package com.lms.outbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One event, written in the same transaction as the change that caused it.
 *
 * <p>That is the whole point: a notification sent from an after-commit callback is lost if the
 * process dies in between, and nothing is left to show it was ever owed. A row written inside the
 * transaction either commits with the enrolment or does not exist at all.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    /** Doubles as the backoff cap, so a stuck consumer is retried hourly rather than forever. */
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    private OutboxEvent(String type, String payload) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.type = type;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.occurredAt = now;
        this.nextAttemptAt = now;
    }

    public static OutboxEvent of(String type, String payload) {
        return new OutboxEvent(type, payload);
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records a failed attempt and decides whether to try again.
     *
     * <p>Backoff doubles each time so a consumer that is down is not hammered, and stops growing
     * at an hour so recovery does not take a day.
     */
    public void recordFailure(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = truncate(error);
        if (attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        this.status = OutboxStatus.PENDING;
        long seconds = Math.min(1L << Math.min(attempts, 20), MAX_BACKOFF.toSeconds());
        this.nextAttemptAt = Instant.now().plusSeconds(seconds);
    }

    /** Puts a dead-lettered event back in the queue, once somebody has fixed whatever broke. */
    public void requeue() {
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.lastError = null;
    }

    void claim() {
        this.status = OutboxStatus.CLAIMED;
    }

    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "unknown error";
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }
}

package com.lms.outbox.dto;

import com.lms.outbox.model.OutboxEvent;
import com.lms.outbox.model.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The outbox doubles as the audit trail, so this is also the answer to "what did the system
 * actually see". The payload is included: without it the trail cannot be used to diagnose anything.
 */
public record OutboxEventResponse(UUID id, String type, OutboxStatus status, int attempts,
                                  Instant occurredAt, Instant nextAttemptAt, Instant publishedAt,
                                  String lastError, String payload) {

    public static OutboxEventResponse from(OutboxEvent event) {
        return new OutboxEventResponse(event.getId(), event.getType(), event.getStatus(),
                event.getAttempts(), event.getOccurredAt(), event.getNextAttemptAt(),
                event.getPublishedAt(), event.getLastError(), event.getPayload());
    }
}

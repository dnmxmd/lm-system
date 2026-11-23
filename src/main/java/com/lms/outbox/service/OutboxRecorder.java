package com.lms.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.payment.event.PaymentFailed;
import com.lms.payment.event.PaymentSucceeded;
import com.lms.user.event.UserRegistered;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts an event in the outbox, inside the transaction that published it.
 *
 * <p>Plain {@code @EventListener}, not {@code @TransactionalEventListener}: the row has to be part
 * of the same commit. An after-commit listener would be writing it in a second transaction, which
 * is the very gap the outbox exists to close.
 *
 * <p>Publishing services are untouched — they still call {@code publishEvent} and know nothing
 * about durability.
 */
@Component
public class OutboxRecorder {

    private final OutboxStore store;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher publisher;

    public OutboxRecorder(OutboxStore store, ObjectMapper objectMapper,
                          ApplicationEventPublisher publisher) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    @EventListener
    public void on(UserRegistered event) {
        record(event);
    }

    @EventListener
    public void on(EnrollmentCreated event) {
        record(event);
    }

    @EventListener
    public void on(EnrollmentCompleted event) {
        record(event);
    }

    @EventListener
    public void on(PaymentSucceeded event) {
        record(event);
    }

    @EventListener
    public void on(PaymentFailed event) {
        record(event);
    }

    private void record(Object event) {
        try {
            store.record(OutboxEventTypes.nameOf(event), objectMapper.writeValueAsString(event));
            // Wakes the relay once this transaction commits, so delivery does not wait for the
            // next poll. If the nudge is lost with the process, the poll still picks the row up.
            publisher.publishEvent(new OutboxRelay.OutboxNudge());
        } catch (JsonProcessingException ex) {
            // Thrown on purpose. An event that cannot be written is an event that will not be
            // delivered, and failing the whole operation is more honest than losing it quietly.
            throw new IllegalStateException("Could not serialise " + OutboxEventTypes.nameOf(event), ex);
        }
    }
}

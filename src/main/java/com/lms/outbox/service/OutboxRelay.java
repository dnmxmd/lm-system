package com.lms.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.outbox.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes events out of the outbox and gives them to consumers.
 *
 * <p>Holds no transaction of its own. Each step asks {@link OutboxStore} for a short one, so that
 * the part which talks to the outside world — a consumer sending mail — runs with no database
 * connection held.
 *
 * <p>It runs on a timer <em>and</em> is nudged after each commit. The timer is what makes delivery
 * reliable; the nudge is what stops a welcome email waiting a second for the next tick. Neither
 * alone would do: a nudge can be lost with the process, and a timer alone feels broken.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final List<OutboxConsumer> consumers;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    /** One pass at a time. Overlapping passes would just contend over the same claims. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxRelay(OutboxStore store, List<OutboxConsumer> consumers, ObjectMapper objectMapper,
                       OutboxProperties properties) {
        this.store = store;
        this.consumers = consumers;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${lms.outbox.poll-interval:PT5S}")
    public void scheduledPass() {
        if (!properties.deliversAutomatically()) {
            return;
        }
        store.releaseStaleClaims();
        deliverDue();
    }

    /** Nudged after a commit so delivery does not wait for the next tick. */
    @TransactionalEventListener
    @Async
    public void onAnythingCommitted(OutboxNudge nudge) {
        if (properties.deliversAutomatically()) {
            deliverDue();
        }
    }

    public int deliverDue() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<UUID> claimed = store.claimDue();
            int delivered = 0;
            for (UUID id : claimed) {
                if (deliver(id)) {
                    delivered++;
                }
            }
            return delivered;
        } finally {
            running.set(false);
        }
    }

    private boolean deliver(UUID id) {
        OutboxEvent event = store.require(id);
        try {
            Object payload = objectMapper.readValue(event.getPayload(),
                    OutboxEventTypes.classOf(event.getType()));
            for (OutboxConsumer consumer : consumers) {
                if (consumer.supports(event.getType())) {
                    consumer.consume(payload);
                }
            }
            store.markPublished(id);
            return true;
        } catch (Exception ex) {
            // Any consumer failing leaves the event to be tried again. Consumers therefore have to
            // tolerate being handed the same event twice.
            log.warn("Outbox event {} ({}) could not be delivered: {}", id, event.getType(),
                    ex.getMessage());
            store.recordFailure(id, ex.toString());
            return false;
        }
    }

    @Scheduled(cron = "${lms.outbox.purge-cron:0 15 3 * * *}")
    public void purgeDelivered() {
        int removed = store.purgeDelivered();
        if (removed > 0) {
            log.info("Removed {} delivered outbox events past their retention", removed);
        }
    }

    /** Marker published after a commit purely to wake the relay. Carries nothing. */
    public record OutboxNudge() {
    }
}

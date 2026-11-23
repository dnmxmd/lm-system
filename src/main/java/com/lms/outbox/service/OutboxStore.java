package com.lms.outbox.service;

import com.lms.common.error.NotFoundException;
import com.lms.outbox.model.OutboxEvent;
import com.lms.outbox.model.OutboxStatus;
import com.lms.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every database touch the outbox makes.
 *
 * <p>Separate from {@link OutboxRelay} so the relay holds no transaction while it is calling
 * consumers. Consumers send mail, and a transaction left open across an SMTP call is how one slow
 * mail server becomes a connection-pool outage.
 */
@Service
public class OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);

    private final OutboxEventRepository events;
    private final OutboxProperties properties;

    public OutboxStore(OutboxEventRepository events, OutboxProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    /**
     * Writes the event. Deliberately joins the caller's transaction: that is what makes the row
     * commit with the change it describes, or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String type, String payload) {
        events.save(OutboxEvent.of(type, payload));
    }

    /**
     * Takes a lease on whatever is ready to go out, including events whose previous holder has
     * stopped. Only events this call actually won are returned.
     */
    @Transactional
    public List<ClaimedEvent> claimDue() {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.claimTimeout());
        Pageable batch = PageRequest.of(0, properties.batchSize());

        List<ClaimedEvent> claimed = new ArrayList<>();
        for (UUID id : events.findClaimableIds(OutboxStatus.PENDING, OutboxStatus.CLAIMED, now, batch)) {
            UUID claimId = UUID.randomUUID();
            if (events.claim(id, claimId, expiresAt, now, OutboxStatus.PENDING, OutboxStatus.CLAIMED) == 1) {
                OutboxEvent event = require(id);
                claimed.add(new ClaimedEvent(id, claimId, event.getType(), event.getPayload()));
            }
        }
        return claimed;
    }

    @Transactional(readOnly = true)
    public OutboxEvent require(UUID id) {
        return events.findById(id).orElseThrow(() -> NotFoundException.of("Outbox event", id));
    }

    /**
     * @return false when the claim is no longer ours — somebody else recovered the event after our
     *         lease expired, and their result stands rather than ours.
     */
    @Transactional
    public boolean markPublished(ClaimedEvent claimed) {
        int updated = events.markPublished(claimed.id(), claimed.claimId(), Instant.now(),
                OutboxStatus.PUBLISHED);
        if (updated == 0) {
            log.warn("Outbox event {} was delivered under a lease that had already expired; "
                    + "the result is discarded because another relay owns it now", claimed.id());
        }
        return updated == 1;
    }

    /** @return false when the claim had already been taken over, as above. */
    @Transactional
    public boolean recordFailure(ClaimedEvent claimed, String error) {
        OutboxEvent event = require(claimed.id());
        // Worked out on a copy of the current state, then written only if the claim still holds.
        OutboxEvent projection = OutboxEvent.replayOf(event);
        projection.recordFailure(error, properties.maxAttempts());

        int updated = events.finishWithFailure(claimed.id(), claimed.claimId(), projection.getStatus(),
                projection.getAttempts(), projection.getLastError(), projection.getNextAttemptAt());
        if (updated == 0) {
            log.warn("Outbox event {} failed under an expired lease; another relay owns it now",
                    claimed.id());
            return false;
        }
        if (projection.getStatus() == OutboxStatus.FAILED) {
            log.error("Outbox event {} ({}) gave up after {} attempts: {}",
                    claimed.id(), event.getType(), projection.getAttempts(), error);
        }
        return true;
    }

    /** Brings a backed-off event forward without clearing its attempt count. */
    @Transactional
    public void clearBackoff(UUID id) {
        require(id).clearBackoff();
    }

    @Transactional
    public void requeue(UUID id) {
        require(id).requeue();
    }

    @Transactional
    public int purgeDelivered() {
        return events.deletePublishedBefore(OutboxStatus.PUBLISHED,
                Instant.now().minus(properties.retention()));
    }

    @Transactional(readOnly = true)
    public Page<OutboxEvent> list(OutboxStatus status, Pageable pageable) {
        return status == null
                ? events.findAllByOrderByOccurredAtDesc(pageable)
                : events.findByStatusOrderByOccurredAtDesc(status, pageable);
    }
}

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

    @Transactional
    public List<UUID> claimDue() {
        Pageable batch = PageRequest.of(0, properties.batchSize());
        return events.findDueIds(OutboxStatus.PENDING, Instant.now(), batch).stream()
                .filter(id -> events.claim(id, OutboxStatus.PENDING, OutboxStatus.CLAIMED) == 1)
                .toList();
    }

    @Transactional(readOnly = true)
    public OutboxEvent require(UUID id) {
        return events.findById(id).orElseThrow(() -> NotFoundException.of("Outbox event", id));
    }

    @Transactional
    public void markPublished(UUID id) {
        require(id).markPublished();
    }

    @Transactional
    public void recordFailure(UUID id, String error) {
        OutboxEvent event = require(id);
        event.recordFailure(error, properties.maxAttempts());
        if (event.getStatus() == OutboxStatus.FAILED) {
            log.error("Outbox event {} ({}) gave up after {} attempts: {}",
                    id, event.getType(), event.getAttempts(), error);
        }
    }

    /**
     * Frees events a previous relay claimed and never finished.
     *
     * <p>Without this, a process killed mid-delivery leaves its claimed rows stranded: nobody else
     * will touch them, so the event is as lost as it would have been with no outbox at all.
     */
    @Transactional
    public int releaseStaleClaims() {
        Instant staleBefore = Instant.now().minus(properties.claimTimeout());
        List<UUID> stale = events.findStaleClaims(OutboxStatus.CLAIMED, staleBefore,
                PageRequest.of(0, properties.batchSize()));
        if (stale.isEmpty()) {
            return 0;
        }
        log.warn("Releasing {} outbox events left claimed by a relay that did not finish", stale.size());
        return events.releaseClaims(stale, OutboxStatus.PENDING);
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

package com.lms.outbox.repository;

import com.lms.outbox.model.OutboxEvent;
import com.lms.outbox.model.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Statuses are bound as parameters rather than written as enum literals in the JPQL. The literal
 * form parses but does not reliably match, which fails silently: the relay simply finds no work.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Work a relay should pick up: never-claimed events that are due, and events whose claim has
     * run out because the relay holding it stopped.
     */
    @Query("""
            select e.id from OutboxEvent e
            where (e.status = :pending and e.nextAttemptAt <= :now)
               or (e.status = :claimed and e.claimExpiresAt <= :now)
            order by e.occurredAt asc
            """)
    List<UUID> findClaimableIds(@Param("pending") OutboxStatus pending,
                                @Param("claimed") OutboxStatus claimed,
                                @Param("now") Instant now, Pageable batch);

    /**
     * Takes a lease on one event, atomically.
     *
     * <p>A conditional update rather than a lock hint: it behaves identically on PostgreSQL and
     * H2, and two relays racing resolve by one of them getting zero rows back. The row is claimable
     * if nobody holds it, or if the holder's lease has expired — which is how an event survives
     * the relay that was carrying it dying.
     *
     * @return 1 when this caller now owns the event under {@code claimId}
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OutboxEvent e
               set e.status = :claimed, e.claimId = :claimId, e.claimExpiresAt = :expiresAt
             where e.id = :id
               and ((e.status = :pending and e.nextAttemptAt <= :now)
                    or (e.status = :claimed and e.claimExpiresAt <= :now))
            """)
    int claim(@Param("id") UUID id, @Param("claimId") UUID claimId,
              @Param("expiresAt") Instant expiresAt, @Param("now") Instant now,
              @Param("pending") OutboxStatus pending, @Param("claimed") OutboxStatus claimed);

    /**
     * Finishes a claim, but only the one named.
     *
     * <p>This is what stops a late answer from a stalled relay landing on an event somebody else
     * has since taken over and already published.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OutboxEvent e
               set e.status = :published, e.publishedAt = :now,
                   e.lastError = null, e.claimId = null, e.claimExpiresAt = null
             where e.id = :id and e.claimId = :claimId
            """)
    int markPublished(@Param("id") UUID id, @Param("claimId") UUID claimId,
                      @Param("now") Instant now, @Param("published") OutboxStatus published);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OutboxEvent e
               set e.status = :status, e.attempts = :attempts, e.lastError = :error,
                   e.nextAttemptAt = :nextAttemptAt, e.claimId = null, e.claimExpiresAt = null
             where e.id = :id and e.claimId = :claimId
            """)
    int finishWithFailure(@Param("id") UUID id, @Param("claimId") UUID claimId,
                          @Param("status") OutboxStatus status, @Param("attempts") int attempts,
                          @Param("error") String error,
                          @Param("nextAttemptAt") Instant nextAttemptAt);

    Page<OutboxEvent> findByStatusOrderByOccurredAtDesc(OutboxStatus status, Pageable pageable);

    Page<OutboxEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = :published and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("published") OutboxStatus published, @Param("cutoff") Instant cutoff);
}

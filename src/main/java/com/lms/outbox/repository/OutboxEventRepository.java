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

    @Query("""
            select e.id from OutboxEvent e
            where e.status = :status and e.nextAttemptAt <= :now
            order by e.occurredAt asc
            """)
    List<UUID> findDueIds(@Param("status") OutboxStatus status, @Param("now") Instant now, Pageable batch);

    /**
     * Claims one row, atomically.
     *
     * <p>A conditional update rather than a lock hint: it behaves identically on PostgreSQL and
     * H2, and two instances racing for the same event resolve by one of them getting zero rows
     * back. Returning 1 means this caller owns the event and nobody else will deliver it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OutboxEvent e set e.status = :claimed
             where e.id = :id and e.status = :pending
            """)
    int claim(@Param("id") UUID id, @Param("pending") OutboxStatus pending,
              @Param("claimed") OutboxStatus claimed);

    /** Rows a relay claimed and never finished, because the process died mid-delivery. */
    @Query("""
            select e.id from OutboxEvent e
            where e.status = :claimed and e.nextAttemptAt <= :staleBefore
            """)
    List<UUID> findStaleClaims(@Param("claimed") OutboxStatus claimed,
                               @Param("staleBefore") Instant staleBefore, Pageable batch);

    @Modifying(clearAutomatically = true)
    @Query("update OutboxEvent e set e.status = :pending where e.id in :ids")
    int releaseClaims(@Param("ids") List<UUID> ids, @Param("pending") OutboxStatus pending);

    Page<OutboxEvent> findByStatusOrderByOccurredAtDesc(OutboxStatus status, Pageable pageable);

    Page<OutboxEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = :published and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("published") OutboxStatus published, @Param("cutoff") Instant cutoff);
}

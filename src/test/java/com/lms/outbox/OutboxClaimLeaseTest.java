package com.lms.outbox;

import com.lms.outbox.model.OutboxEvent;
import com.lms.outbox.model.OutboxStatus;
import com.lms.outbox.repository.OutboxEventRepository;
import com.lms.outbox.service.ClaimedEvent;
import com.lms.outbox.service.OutboxStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two relays competing for the same event, against real PostgreSQL.
 *
 * <p>These are the cases a single-threaded H2 test cannot speak to: whether the conditional update
 * really serialises two claimants, whether an expired lease can be recovered, and whether a relay
 * that stalls past its lease can come back and trample the result of whoever took over.
 */
@SpringBootTest
@ActiveProfiles("realinfra")
@TestPropertySource(properties = "lms.outbox.claim-timeout=PT1S")
class OutboxClaimLeaseTest {

    @Autowired
    private OutboxStore store;
    @Autowired
    private OutboxEventRepository events;
    @Autowired
    private TransactionTemplate transactions;

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(reachable("localhost", 5432), "PostgreSQL is not running on localhost:5432");
        assumeTrue(reachable("localhost", 6379), "Redis is not running on localhost:6379");
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException unreachable) {
            return false;
        }
    }

    /** Writes one event and returns its id, without going through a business flow. */
    private UUID givenAnEvent() {
        String marker = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status ->
                store.record("UserRegistered", "{\"marker\":\"" + marker + "\"}"));
        return events.findAll().stream()
                .filter(event -> event.getPayload().contains(marker))
                .findFirst().orElseThrow().getId();
    }

    @Test
    void two_relays_racing_for_one_event_produce_a_single_valid_claim() {
        UUID id = givenAnEvent();
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(60);

        int first = transactions.execute(status -> events.claim(id, UUID.randomUUID(), expiry, now,
                OutboxStatus.PENDING, OutboxStatus.CLAIMED));
        int second = transactions.execute(status -> events.claim(id, UUID.randomUUID(), expiry, now,
                OutboxStatus.PENDING, OutboxStatus.CLAIMED));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the event is already claimed and the lease has not expired").isZero();
    }

    @Test
    void a_second_relay_recovers_the_event_once_the_lease_expires() throws Exception {
        UUID id = givenAnEvent();

        List<ClaimedEvent> firstPass = store.claimDue();
        ClaimedEvent mine = firstPass.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();

        // Nothing else may touch it while the lease holds.
        assertThat(store.claimDue()).noneMatch(c -> c.id().equals(id));

        // claim-timeout is one second for this test.
        Thread.sleep(1200);

        List<ClaimedEvent> recovery = store.claimDue();
        assertThat(recovery)
                .as("a relay that stopped must not strand the event forever")
                .anyMatch(c -> c.id().equals(id));
        assertThat(recovery.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().claimId())
                .isNotEqualTo(mine.claimId());
    }

    @Test
    void a_late_answer_cannot_overwrite_the_new_owners_result() throws Exception {
        UUID id = givenAnEvent();
        ClaimedEvent stalled = store.claimDue().stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();

        Thread.sleep(1200);
        ClaimedEvent newOwner = store.claimDue().stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        assertThat(store.markPublished(newOwner)).isTrue();

        // The original relay finally comes back with its answer.
        boolean accepted = store.markPublished(stalled);

        assertThat(accepted).as("the stalled relay no longer owns this event").isFalse();
        assertThat(events.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void a_late_failure_cannot_move_a_published_event_back_to_pending() throws Exception {
        UUID id = givenAnEvent();
        ClaimedEvent stalled = store.claimDue().stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();

        Thread.sleep(1200);
        ClaimedEvent newOwner = store.claimDue().stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        store.markPublished(newOwner);

        boolean accepted = store.recordFailure(stalled, "timed out talking to the mail server");

        assertThat(accepted).isFalse();
        OutboxEvent after = events.findById(id).orElseThrow();
        assertThat(after.getStatus())
                .as("a delivered event must not be dragged back into the queue")
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void finishing_a_claim_clears_the_lease() {
        UUID id = givenAnEvent();
        ClaimedEvent claimed = store.claimDue().stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();

        store.markPublished(claimed);

        OutboxEvent after = events.findById(id).orElseThrow();
        assertThat(after.getClaimId()).isNull();
        assertThat(after.getClaimExpiresAt()).isNull();
    }
}

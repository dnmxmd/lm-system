package com.lms.outbox;

import com.lms.outbox.model.OutboxEvent;
import com.lms.outbox.model.OutboxStatus;
import com.lms.outbox.service.ClaimedEvent;
import com.lms.outbox.service.OutboxConsumer;
import com.lms.outbox.service.OutboxRelay;
import com.lms.outbox.service.OutboxStore;
import com.lms.user.dto.RegisterRequest;
import com.lms.user.event.UserRegistered;
import com.lms.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox exists so that nothing handled after a commit can be lost. These tests are about the
 * parts of that claim which are easy to get wrong: the row committing with the change rather than
 * beside it, a failing consumer being retried instead of dropped, and nobody being delivered twice.
 */
@SpringBootTest
@ActiveProfiles("schema")
@Import(OutboxTest.FlakyConsumerConfig.class)
class OutboxTest {

    @Autowired
    private UserService users;
    @Autowired
    private OutboxStore store;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private FlakyConsumer consumer;
    @Autowired
    private TransactionTemplate transactions;

    /** Stands in for a consumer that can fail, so retry and dead-lettering can be observed. */
    @TestConfiguration
    static class FlakyConsumerConfig {
        @Bean
        FlakyConsumer flakyConsumer() {
            return new FlakyConsumer();
        }
    }

    static class FlakyConsumer implements OutboxConsumer {
        final List<Object> delivered = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresToThrow = new AtomicInteger(0);

        @Override
        public boolean supports(String eventType) {
            return UserRegistered.class.getSimpleName().equals(eventType);
        }

        @Override
        public void consume(Object event) {
            if (failuresToThrow.getAndDecrement() > 0) {
                throw new IllegalStateException("mail server is down");
            }
            delivered.add(event);
        }
    }

    @BeforeEach
    void resetConsumer() {
        consumer.delivered.clear();
        consumer.failuresToThrow.set(0);
    }

    private String register() {
        String email = "outbox-" + UUID.randomUUID() + "@example.com";
        users.register(new RegisterRequest(email, "hunter2024", "Outbox Person", null));
        return email;
    }

    @Test
    void registering_writes_an_event_that_is_delivered_and_marked() {
        String email = register();

        relay.deliverDue();

        assertThat(consumer.delivered)
                .anySatisfy(event -> assertThat(((UserRegistered) event).email()).isEqualTo(email));
        assertThat(pending()).noneSatisfy(event ->
                assertThat(event.getPayload()).contains(email));
    }

    @Test
    void a_consumer_that_fails_gets_the_event_again_rather_than_losing_it() {
        consumer.failuresToThrow.set(1);
        String email = register();

        relay.deliverDue();

        // Still owed, backed off, and recorded why.
        OutboxEvent afterFailure = find(email);
        assertThat(afterFailure.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("mail server is down");
        assertThat(consumer.delivered).isEmpty();

        // Once the consumer recovers, the event is delivered on a later pass.
        store.requeue(afterFailure.getId());
        relay.deliverDue();

        assertThat(consumer.delivered)
                .anySatisfy(event -> assertThat(((UserRegistered) event).email()).isEqualTo(email));
        assertThat(find(email).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void a_failure_backs_off_and_eventually_gives_up() {
        OutboxEvent event = OutboxEvent.of("UserRegistered", "{}");
        int maxAttempts = 3;

        event.recordFailure("mail server is down", maxAttempts);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        Instant firstBackoff = event.getNextAttemptAt();
        assertThat(firstBackoff).isAfter(event.getOccurredAt());

        event.recordFailure("still down", maxAttempts);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextAttemptAt())
                .as("backoff grows so a broken consumer is not hammered")
                .isAfterOrEqualTo(firstBackoff);

        event.recordFailure("gave up", maxAttempts);
        assertThat(event.getStatus())
                .as("it must end up somewhere a human will find it")
                .isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getLastError()).contains("gave up");
    }

    @Test
    void requeueing_a_dead_lettered_event_gives_it_a_clean_slate() {
        OutboxEvent event = OutboxEvent.of("UserRegistered", "{}");
        event.recordFailure("broken", 1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);

        event.requeue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void a_delivered_event_is_not_delivered_a_second_time() {
        String email = register();

        relay.deliverDue();
        relay.deliverDue();
        relay.deliverDue();

        assertThat(consumer.delivered)
                .filteredOn(event -> ((UserRegistered) event).email().equals(email))
                .hasSize(1);
    }

    @Test
    void claiming_an_event_twice_only_succeeds_once() {
        String email = register();
        UUID id = find(email).getId();

        List<UUID> first = store.claimDue().stream().map(ClaimedEvent::id).toList();
        List<UUID> second = store.claimDue().stream().map(ClaimedEvent::id).toList();

        assertThat(first).contains(id);
        assertThat(second).as("the lease is still held").doesNotContain(id);
    }

    @Test
    void the_event_is_written_in_the_callers_transaction_not_beside_it() {
        // MANDATORY: recording outside a transaction is a programming error, because a row written
        // in its own transaction is exactly the gap the outbox exists to close.
        assertThatThrownBy(() -> store.record("UserRegistered", "{}"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void an_event_rolled_back_with_its_change_leaves_nothing_behind() {
        long before = countAll();

        // A real transaction, via the template: calling a @Transactional method on this test
        // object would bypass the proxy and run with no transaction at all.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            users.register(new RegisterRequest("rollback-" + UUID.randomUUID() + "@example.com",
                    "hunter2024", "Rolled Back", null));
            throw new IllegalStateException("something went wrong after the event was recorded");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countAll())
                .as("the account was never created, so nothing should be owed about it")
                .isEqualTo(before);
    }

    private List<OutboxEvent> pending() {
        return store.list(OutboxStatus.PENDING, Pageable.ofSize(200)).getContent();
    }

    private long countAll() {
        return store.list(null, Pageable.ofSize(1)).getTotalElements();
    }

    private OutboxEvent find(String email) {
        return store.list(null, Pageable.ofSize(500)).getContent().stream()
                .filter(event -> event.getPayload().contains(email))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outbox event mentions " + email));
    }
}

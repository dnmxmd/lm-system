package com.lms.notification;

import com.lms.outbox.model.OutboxEvent;
import com.lms.outbox.model.OutboxStatus;
import com.lms.outbox.service.OutboxRelay;
import com.lms.outbox.service.OutboxStore;
import com.lms.user.dto.RegisterRequest;
import com.lms.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mail that fails to send must be tried again.
 *
 * <p>This is the real notification consumer with a real {@code JavaMailSender} that throws, rather
 * than a stand-in consumer: the bug being guarded against lived in the gap between the two, where
 * {@code EmailService} caught the exception and the relay was told everything was fine.
 */
@SpringBootTest
@ActiveProfiles("schema")
@TestPropertySource(properties = {
        // Non-blank, so EmailService uses the sender below instead of logging.
        "spring.mail.host=localhost",
        "lms.outbox.max-attempts=3"
})
@Import(FailedMailRetryTest.FailingMailConfig.class)
class FailedMailRetryTest {

    @Autowired
    private UserService users;
    @Autowired
    private OutboxStore store;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private CountingMailSender mailSender;

    @TestConfiguration
    static class FailingMailConfig {
        @Bean
        @Primary
        CountingMailSender countingMailSender() {
            return new CountingMailSender();
        }
    }

    /** A mail server that is down for the first {@code failuresToThrow} attempts. */
    static class CountingMailSender extends org.springframework.mail.javamail.JavaMailSenderImpl {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger failuresToThrow = new AtomicInteger();

        @Override
        public void send(SimpleMailMessage... messages) {
            attempts.incrementAndGet();
            if (failuresToThrow.getAndDecrement() > 0) {
                throw new MailSendException("mail server refused the connection");
            }
        }
    }

    @BeforeEach
    void reset() {
        mailSender.attempts.set(0);
        mailSender.failuresToThrow.set(0);
    }

    private String register() {
        String email = "mail-" + UUID.randomUUID() + "@example.com";
        users.register(new RegisterRequest(email, "hunter2024", "Mail Person", null));
        return email;
    }

    @Test
    void a_failed_send_is_counted_and_scheduled_for_another_try() {
        mailSender.failuresToThrow.set(1);
        String email = register();

        relay.deliverDue();

        OutboxEvent event = find(email);
        assertThat(mailSender.attempts.get()).as("the send was attempted").isEqualTo(1);
        assertThat(event.getStatus())
                .as("a send that failed must not be recorded as delivered")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("mail server refused the connection");
    }

    @Test
    void a_successful_retry_marks_the_event_published() {
        mailSender.failuresToThrow.set(1);
        String email = register();

        relay.deliverDue();
        assertThat(find(email).getStatus()).isEqualTo(OutboxStatus.PENDING);

        // The mail server comes back; the backoff is cleared the way an operator would.
        store.requeue(find(email).getId());
        relay.deliverDue();

        assertThat(mailSender.attempts.get()).isEqualTo(2);
        assertThat(find(email).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void a_mail_server_that_never_recovers_dead_letters_the_event() {
        mailSender.failuresToThrow.set(Integer.MAX_VALUE);
        String email = register();

        // max-attempts is 3 for this test. The backoff is cleared between passes so the test does
        // not have to wait it out; the attempt count still climbs.
        for (int pass = 0; pass < 5 && find(email).getStatus() != OutboxStatus.FAILED; pass++) {
            relay.deliverDue();
            OutboxEvent event = find(email);
            if (event.getStatus() == OutboxStatus.PENDING) {
                store.clearBackoff(event.getId());
            }
        }

        assertThat(find(email).getStatus())
                .as("somebody has to be told that this mail is never going out")
                .isEqualTo(OutboxStatus.FAILED);
    }

    private OutboxEvent find(String email) {
        return store.list(null, Pageable.ofSize(500)).getContent().stream()
                .filter(event -> event.getPayload().contains(email))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outbox event mentions " + email));
    }
}

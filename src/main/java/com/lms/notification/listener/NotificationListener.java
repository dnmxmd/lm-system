package com.lms.notification.listener;

import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.notification.service.EmailService;
import com.lms.notification.service.EmailTemplates;
import com.lms.notification.service.RecipientLookup;
import com.lms.outbox.service.OutboxConsumer;
import com.lms.payment.event.PaymentFailed;
import com.lms.payment.event.PaymentSucceeded;
import com.lms.user.event.UserRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns what happened into the mail people receive.
 *
 * <p>Delivered from the outbox rather than straight off the event bus. The difference matters: an
 * after-commit listener that dies mid-send leaves nothing behind, whereas an outbox row is still
 * there to be retried.
 *
 * <p>Nothing here is transactional. {@link RecipientLookup} gathers the address and course title in
 * a short transaction of its own and returns a plain record, so the mail server is contacted with
 * no database connection held.
 *
 * <p>A failure throws, which tells the relay to try again later. Delivery is therefore at least
 * once: a send that succeeded and then failed to be marked will send a second copy. For mail that
 * is the right way round — a duplicate welcome message is a nuisance, a missing one is a bug.
 */
@Component
public class NotificationListener implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private static final Set<String> HANDLED = Set.of(
            UserRegistered.class.getSimpleName(),
            EnrollmentCreated.class.getSimpleName(),
            EnrollmentCompleted.class.getSimpleName(),
            PaymentSucceeded.class.getSimpleName(),
            PaymentFailed.class.getSimpleName());

    private final EmailService email;
    private final RecipientLookup recipients;

    public NotificationListener(EmailService email, RecipientLookup recipients) {
        this.email = email;
        this.recipients = recipients;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void consume(Object event) {
        switch (event) {
            // Everything needed is on the event itself; no lookup, no transaction.
            case UserRegistered registered -> email.send(registered.email(),
                    EmailTemplates.welcome(registered.fullName(), registered.role().name()));

            case EnrollmentCreated enrolled -> notify(enrolled.studentId(), enrolled.courseId(),
                    title -> enrolled.requiresPayment()
                            ? EmailTemplates.enrolmentPending(title)
                            : EmailTemplates.enrolmentActive(title));

            case PaymentSucceeded paid ->
                    notify(paid.studentId(), paid.courseId(), EmailTemplates::enrolmentActive);

            case PaymentFailed declined -> notify(declined.studentId(), declined.courseId(),
                    title -> EmailTemplates.paymentFailed(title, declined.reason()));

            case EnrollmentCompleted finished ->
                    notify(finished.studentId(), finished.courseId(), EmailTemplates::courseCompleted);

            default -> log.warn("No notification is defined for {}", event.getClass().getSimpleName());
        }
    }

    private void notify(UUID studentId, UUID courseId,
                        Function<String, EmailTemplates.Message> compose) {
        recipients.find(studentId, courseId)
                .ifPresent(recipient ->
                        email.send(recipient.address(), compose.apply(recipient.courseTitle())));
    }
}

package com.lms.notification.listener;

import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.notification.service.EmailService;
import com.lms.notification.service.EmailTemplates;
import com.lms.notification.service.RecipientLookup;
import com.lms.payment.event.PaymentFailed;
import com.lms.payment.event.PaymentSucceeded;
import com.lms.user.event.UserRegistered;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;
import java.util.function.Function;

/**
 * Turns what happened into the mail people receive.
 *
 * <p>Three things are deliberate here. Listeners run <em>after</em> the transaction commits, so
 * nobody is told about something that was rolled back. They run on a separate thread, so a slow
 * mail server does not hold up the learner's request. And none of them is transactional: the data
 * is gathered by {@link RecipientLookup} in a short transaction of its own, which ends before the
 * mail server is contacted.
 */
@Component
public class NotificationListener {

    private final EmailService email;
    private final RecipientLookup recipients;

    public NotificationListener(EmailService email, RecipientLookup recipients) {
        this.email = email;
        this.recipients = recipients;
    }

    @TransactionalEventListener
    @Async
    public void on(UserRegistered event) {
        // Everything needed is already on the event; no lookup, no transaction.
        email.send(event.email(), EmailTemplates.welcome(event.fullName(), event.role().name()));
    }

    @TransactionalEventListener
    @Async
    public void on(EnrollmentCreated event) {
        notify(event.studentId(), event.courseId(), title -> event.requiresPayment()
                ? EmailTemplates.enrolmentPending(title)
                : EmailTemplates.enrolmentActive(title));
    }

    @TransactionalEventListener
    @Async
    public void on(PaymentSucceeded event) {
        notify(event.studentId(), event.courseId(), EmailTemplates::enrolmentActive);
    }

    @TransactionalEventListener
    @Async
    public void on(PaymentFailed event) {
        notify(event.studentId(), event.courseId(),
                title -> EmailTemplates.paymentFailed(title, event.reason()));
    }

    @TransactionalEventListener
    @Async
    public void on(EnrollmentCompleted event) {
        notify(event.studentId(), event.courseId(), EmailTemplates::courseCompleted);
    }

    private void notify(UUID studentId, UUID courseId,
                        Function<String, EmailTemplates.Message> compose) {
        recipients.find(studentId, courseId)
                .ifPresent(recipient ->
                        email.send(recipient.address(), compose.apply(recipient.courseTitle())));
    }
}

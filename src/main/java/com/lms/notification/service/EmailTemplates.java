package com.lms.notification.service;

import java.util.Locale;

/**
 * The wording of every message, in one place.
 *
 * <p>Plain text on purpose: swapping these for rendered templates later touches only this class.
 */
public final class EmailTemplates {

    private EmailTemplates() {
    }

    public record Message(String subject, String body) {
    }

    public static Message welcome(String fullName, String role) {
        return new Message("Welcome to the LMS", """
                Hello %s,

                Your account is ready. You are signed up as a %s. Have a look at the catalog —
                free courses open straight away, paid ones once the payment goes through.
                """.formatted(fullName, role.toLowerCase(Locale.ROOT)));
    }

    public static Message enrolmentPending(String courseTitle) {
        return new Message("One step left on your enrolment", """
                You have enrolled in "%s".

                We are waiting for your payment to settle. As soon as it does the lessons open up.
                """.formatted(courseTitle));
    }

    public static Message enrolmentActive(String courseTitle) {
        return new Message("You are in", """
                Your enrolment in "%s" is active. Every lesson is open to you now.
                """.formatted(courseTitle));
    }

    public static Message courseCompleted(String courseTitle) {
        return new Message("Course finished", """
                You have completed every lesson of "%s". Well done.

                Your certificate is available from your enrolment page.
                """.formatted(courseTitle));
    }

    public static Message paymentFailed(String courseTitle, String reason) {
        return new Message("Your payment did not go through", """
                The payment for "%s" was not completed: %s

                Your place is still reserved. Try again from your enrolments page.
                """.formatted(courseTitle, reason));
    }
}

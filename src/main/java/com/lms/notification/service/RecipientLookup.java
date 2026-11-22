package com.lms.notification.service;

import com.lms.course.service.CourseCatalogReader;
import com.lms.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Collects everything a message needs, in one short transaction.
 *
 * <p>Separated from the listener so that the database connection is given back <em>before</em>
 * anyone talks to a mail server. Holding a transaction open across an SMTP call means one slow
 * mail server can tie up the connection pool.
 */
@Service
public class RecipientLookup {

    private static final Logger log = LoggerFactory.getLogger(RecipientLookup.class);

    private final UserService users;
    private final CourseCatalogReader courses;

    public RecipientLookup(UserService users, CourseCatalogReader courses) {
        this.users = users;
        this.courses = courses;
    }

    public record Recipient(String address, String courseTitle) {
    }

    @Transactional(readOnly = true)
    public Optional<Recipient> find(UUID userId, UUID courseId) {
        Optional<String> address = users.emailOf(userId);
        if (address.isEmpty()) {
            log.warn("No address on file for user {}; notification skipped", userId);
            return Optional.empty();
        }
        return Optional.of(new Recipient(address.get(), titleOf(courseId)));
    }

    /** Falls back to the id rather than failing: a missing title is no reason to drop the mail. */
    private String titleOf(UUID courseId) {
        try {
            return courses.syllabusById(courseId).title();
        } catch (RuntimeException ex) {
            return courseId.toString();
        }
    }
}

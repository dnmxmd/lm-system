package com.lms.course.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The few things other features need to know about a course.
 *
 * <p>Deliberately not the JPA entity: enrolment should not be able to reach into the catalog's
 * storage model, and widening this record is a visible decision rather than an accident.
 */
public record CourseFacts(UUID courseId, UUID instructorId, boolean openForEnrolment, boolean free,
                          BigDecimal price, String currency, List<UUID> lessonIds) {

    public int lessonCount() {
        return lessonIds.size();
    }

    public boolean contains(UUID lessonId) {
        return lessonIds.contains(lessonId);
    }
}

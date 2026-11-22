package com.lms.common.access;

import java.util.UUID;

/**
 * Whether somebody is entitled to a course's actual content, as opposed to its description.
 *
 * <p>Declared here rather than in either feature on purpose. The catalog has to ask the question
 * and enrolment is the only thing that can answer it, so putting the interface in one of them
 * would point the two at each other.
 */
public interface CourseAccess {

    boolean maySeeContentOf(UUID courseId, UUID userId);
}

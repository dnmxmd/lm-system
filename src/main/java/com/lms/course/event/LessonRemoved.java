package com.lms.course.event;

import java.util.UUID;

/**
 * A lesson has been taken out of a course that people may already be part way through.
 *
 * <p>It carries the remaining count so listeners do not have to go back and ask.
 */
public record LessonRemoved(UUID courseId, UUID lessonId, int remainingLessons) {
}

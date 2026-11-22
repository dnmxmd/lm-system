package com.lms.course.dto;

import com.lms.course.model.Lesson;

import java.io.Serializable;
import java.util.UUID;

/**
 * A lesson as a client sees it.
 *
 * <p>{@code contentUrl} is null unless the viewer is entitled to the content. Titles and lengths
 * are how people decide whether to enrol; the URL is the thing they are deciding to buy.
 */
public record LessonResponse(UUID id, String title, int position, int durationMinutes, String contentUrl)
        implements Serializable {

    /** For the public catalog: everything that helps somebody choose, and nothing more. */
    public static LessonResponse syllabus(Lesson lesson) {
        return new LessonResponse(lesson.getId(), lesson.getTitle(), lesson.getPosition(),
                lesson.getDurationMinutes(), null);
    }

    /** For the instructor who owns the course, and for learners whose enrolment is active. */
    public static LessonResponse withContent(Lesson lesson) {
        return new LessonResponse(lesson.getId(), lesson.getTitle(), lesson.getPosition(),
                lesson.getDurationMinutes(), lesson.getContentUrl());
    }
}

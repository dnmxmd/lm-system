package com.lms.course.dto;

import com.lms.course.model.Course;
import com.lms.course.model.CourseLevel;
import com.lms.course.model.CourseStatus;
import com.lms.course.model.Lesson;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Full course detail. {@link Serializable} because the syllabus form of this is what gets cached.
 *
 * <p>Built through {@link #syllabus} or {@link #withContent} rather than one method with a flag,
 * so that every call site has to say which audience it is answering.
 */
public record CourseResponse(UUID id, UUID instructorId, String title, String slug, String summary,
                             BigDecimal price, String currency, boolean free, CourseLevel level,
                             CourseStatus status, int lessonCount, int totalDurationMinutes,
                             List<LessonResponse> lessons, Instant createdAt, Instant updatedAt,
                             Instant publishedAt) implements Serializable {

    /** The public view: lesson titles and lengths, but no content URLs. */
    public static CourseResponse syllabus(Course course) {
        return build(course, LessonResponse::syllabus);
    }

    /** For the owning instructor, an admin, or a learner with an active enrolment. */
    public static CourseResponse withContent(Course course) {
        return build(course, LessonResponse::withContent);
    }

    private static CourseResponse build(Course course, Function<Lesson, LessonResponse> asResponse) {
        List<Lesson> lessons = course.getLessons();
        return new CourseResponse(course.getId(), course.getInstructorId(), course.getTitle(),
                course.getSlug(), course.getSummary(), course.getPrice().amount(),
                course.getPrice().currencyCode(), course.isFree(), course.getLevel(), course.getStatus(),
                lessons.size(), course.totalDurationMinutes(),
                lessons.stream().map(asResponse).toList(),
                course.getCreatedAt(), course.getUpdatedAt(), course.getPublishedAt());
    }
}

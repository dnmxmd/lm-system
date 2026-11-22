package com.lms.course.dto;

import com.lms.course.model.Course;
import com.lms.course.model.CourseLevel;
import com.lms.course.model.CourseStatus;

import java.math.BigDecimal;
import java.util.UUID;

/** A catalog row: enough to render a card, without shipping every lesson of every course. */
public record CourseSummaryResponse(UUID id, UUID instructorId, String title, String slug, String summary,
                                    BigDecimal price, String currency, boolean free, CourseLevel level,
                                    CourseStatus status, int lessonCount, int totalDurationMinutes) {

    public static CourseSummaryResponse from(Course course) {
        return new CourseSummaryResponse(course.getId(), course.getInstructorId(), course.getTitle(),
                course.getSlug(), course.getSummary(), course.getPrice().amount(),
                course.getPrice().currencyCode(), course.isFree(), course.getLevel(), course.getStatus(),
                course.getLessons().size(), course.totalDurationMinutes());
    }
}

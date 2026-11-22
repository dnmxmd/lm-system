package com.lms.course.event;

import java.math.BigDecimal;
import java.util.UUID;

public record CoursePublished(UUID courseId, UUID instructorId, String title,
                              BigDecimal price, String currency) {
}

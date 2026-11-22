package com.lms.analytics.dto;

import com.lms.analytics.model.CourseMetrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourseMetricsResponse(UUID courseId, long enrollments, long completions, long lessonsCompleted,
                                    int completionRatePercent, BigDecimal revenue, String currency,
                                    Instant lastUpdatedAt) {

    public static CourseMetricsResponse from(CourseMetrics metrics) {
        return new CourseMetricsResponse(metrics.getCourseId(), metrics.getEnrollments(),
                metrics.getCompletions(), metrics.getLessonsCompleted(), metrics.completionRatePercent(),
                metrics.getRevenueAmount() == null ? BigDecimal.ZERO : metrics.getRevenueAmount(),
                metrics.getRevenueCurrency(), metrics.getLastUpdatedAt());
    }
}

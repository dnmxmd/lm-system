package com.lms.analytics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Running totals for one course.
 *
 * <p>Read-only from Java's side. The counters are moved by atomic {@code UPDATE ... SET x = x + 1}
 * statements in {@link com.lms.analytics.repository.CourseMetricsRepository}, never by loading this
 * object and saving it back: several learners enrolling in the same course at the same moment is
 * the normal case on launch day, and read-then-write would make them collide.
 */
@Entity
@Table(name = "course_metrics")
public class CourseMetrics {

    @Id
    @Column(name = "course_id")
    private UUID courseId;

    @Column(nullable = false)
    private long enrollments;

    @Column(nullable = false)
    private long completions;

    @Column(name = "lessons_completed", nullable = false)
    private long lessonsCompleted;

    @Column(name = "revenue_amount", precision = 14, scale = 2)
    private BigDecimal revenueAmount;

    @Column(name = "revenue_currency", length = 3)
    private String revenueCurrency;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    protected CourseMetrics() {
        // for JPA
    }

    private CourseMetrics(UUID courseId) {
        this.courseId = courseId;
        this.lastUpdatedAt = Instant.now();
    }

    /** A course nothing has happened to yet. Also what a report falls back to when there is no row. */
    public static CourseMetrics empty(UUID courseId) {
        return new CourseMetrics(courseId);
    }

    /** Share of enrolled learners who finished, 0-100. Zero when nobody has enrolled yet. */
    public int completionRatePercent() {
        if (enrollments == 0) {
            return 0;
        }
        return (int) Math.round(completions * 100.0 / enrollments);
    }

    public UUID getCourseId() {
        return courseId;
    }

    public long getEnrollments() {
        return enrollments;
    }

    public long getCompletions() {
        return completions;
    }

    public long getLessonsCompleted() {
        return lessonsCompleted;
    }

    public BigDecimal getRevenueAmount() {
        return revenueAmount;
    }

    public String getRevenueCurrency() {
        return revenueCurrency;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }
}

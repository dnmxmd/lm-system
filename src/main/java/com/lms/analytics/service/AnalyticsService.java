package com.lms.analytics.service;

import com.lms.analytics.dto.CourseMetricsResponse;
import com.lms.analytics.dto.PlatformOverviewResponse;
import com.lms.analytics.model.CourseMetrics;
import com.lms.analytics.repository.CourseMetricsRepository;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.course.event.CoursePublished;
import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.enrollment.event.LessonCompleted;
import com.lms.payment.event.PaymentSucceeded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Keeps the reporting totals up to date and answers questions about them.
 *
 * <p>The listeners run inside the transaction that produced the event, so a rolled-back enrolment
 * never leaves a count behind. They move the counters with atomic statements rather than by loading
 * and saving, so they add no contention to the request that triggered them.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final CourseMetricsRepository metrics;

    public AnalyticsService(CourseMetricsRepository metrics) {
        this.metrics = metrics;
    }

    /**
     * Creates the row as soon as a course can be enrolled in, so that every later counter move is
     * a plain update with nothing to insert and nothing to race over.
     */
    @EventListener
    @Transactional
    public void on(CoursePublished event) {
        ensureRow(event.courseId());
    }

    @EventListener
    @Transactional
    public void on(EnrollmentCreated event) {
        Instant now = Instant.now();
        apply(event.courseId(), () -> metrics.addEnrollment(event.courseId(), now));
    }

    @EventListener
    @Transactional
    public void on(LessonCompleted event) {
        Instant now = Instant.now();
        apply(event.courseId(), () -> metrics.addLessonCompleted(event.courseId(), now));
    }

    @EventListener
    @Transactional
    public void on(EnrollmentCompleted event) {
        Instant now = Instant.now();
        apply(event.courseId(), () -> metrics.addCompletion(event.courseId(), now));
    }

    @EventListener
    @Transactional
    public void on(PaymentSucceeded event) {
        Instant now = Instant.now();
        IntSupplier add = () -> metrics.addRevenue(event.courseId(), event.amount(), event.currency(), now);
        if (add.getAsInt() == 0) {
            ensureRow(event.courseId());
            if (add.getAsInt() == 0) {
                // The only other way to match no row: this course already reports another currency.
                // shortcut: one currency per course. Revenue in a second one is dropped with this
                // warning rather than converted. Add per-currency totals if that ever happens.
                log.warn("Course {} already reports revenue in another currency; {} {} was not counted",
                        event.courseId(), event.amount(), event.currency());
            }
        }
    }

    @Transactional(readOnly = true)
    public CourseMetricsResponse forCourse(UUID courseId) {
        // A course with no activity reports zeroes rather than a 404: absence of data is data.
        return CourseMetricsResponse.from(metrics.findById(courseId)
                .orElseGet(() -> CourseMetrics.empty(courseId)));
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseMetricsResponse> allCourses(PageQuery query) {
        var page = metrics.findAll(query.toPageable(Sort.by(Sort.Direction.DESC, "enrollments")));
        return PageResponse.of(page, CourseMetricsResponse::from);
    }

    @Transactional(readOnly = true)
    public PlatformOverviewResponse overview() {
        return PlatformOverviewResponse.from(metrics.totals(), metrics.revenueByCurrency());
    }

    /** Courses published before this service existed have no row; create one and apply again. */
    private void apply(UUID courseId, IntSupplier update) {
        if (update.getAsInt() == 0) {
            ensureRow(courseId);
            update.getAsInt();
        }
    }

    private void ensureRow(UUID courseId) {
        if (!metrics.existsById(courseId)) {
            // Flushed now so the update that follows can see it.
            metrics.saveAndFlush(CourseMetrics.empty(courseId));
        }
    }
}

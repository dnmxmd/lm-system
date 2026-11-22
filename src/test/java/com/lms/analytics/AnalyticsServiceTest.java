package com.lms.analytics;

import com.lms.analytics.model.CourseMetrics;
import com.lms.analytics.repository.CourseMetricsRepository;
import com.lms.analytics.service.AnalyticsService;
import com.lms.course.event.CoursePublished;
import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.enrollment.event.LessonCompleted;
import com.lms.payment.event.PaymentSucceeded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final UUID COURSE = UUID.randomUUID();

    @Mock
    private CourseMetricsRepository metrics;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(metrics);
    }

    private static EnrollmentCreated enrolment() {
        return new EnrollmentCreated(UUID.randomUUID(), UUID.randomUUID(), COURSE,
                BigDecimal.ZERO, "USD", false);
    }

    @Test
    void publishing_a_course_opens_its_row_so_later_counting_never_has_to_insert() {
        when(metrics.existsById(COURSE)).thenReturn(false);

        analyticsService.on(new CoursePublished(COURSE, UUID.randomUUID(), "A course",
                BigDecimal.ZERO, "USD"));

        verify(metrics).saveAndFlush(any(CourseMetrics.class));
    }

    @Test
    void an_enrollment_moves_the_counter_without_reading_it_first() {
        when(metrics.addEnrollment(eq(COURSE), any(Instant.class))).thenReturn(1);

        analyticsService.on(enrolment());

        verify(metrics).addEnrollment(eq(COURSE), any(Instant.class));
        verify(metrics, never()).findById(any());
        verify(metrics, never()).save(any());
    }

    @Test
    void a_course_with_no_row_yet_gets_one_and_is_counted_anyway() {
        when(metrics.addEnrollment(eq(COURSE), any(Instant.class))).thenReturn(0, 1);
        when(metrics.existsById(COURSE)).thenReturn(false);

        analyticsService.on(enrolment());

        verify(metrics).saveAndFlush(any(CourseMetrics.class));
        verify(metrics, times(2)).addEnrollment(eq(COURSE), any(Instant.class));
    }

    @Test
    void finishing_a_lesson_and_finishing_a_course_are_counted_separately() {
        when(metrics.addLessonCompleted(eq(COURSE), any(Instant.class))).thenReturn(1);
        when(metrics.addCompletion(eq(COURSE), any(Instant.class))).thenReturn(1);

        analyticsService.on(new LessonCompleted(UUID.randomUUID(), UUID.randomUUID(), COURSE,
                UUID.randomUUID(), 50));
        analyticsService.on(new EnrollmentCompleted(UUID.randomUUID(), UUID.randomUUID(), COURSE));

        verify(metrics).addLessonCompleted(eq(COURSE), any(Instant.class));
        verify(metrics).addCompletion(eq(COURSE), any(Instant.class));
    }

    @Test
    void revenue_is_added_in_one_statement() {
        when(metrics.addRevenue(eq(COURSE), any(), eq("USD"), any(Instant.class))).thenReturn(1);

        analyticsService.on(new PaymentSucceeded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                COURSE, new BigDecimal("49.99"), "USD"));

        verify(metrics).addRevenue(eq(COURSE), eq(new BigDecimal("49.99")), eq("USD"), any(Instant.class));
    }

    @Test
    void revenue_in_a_second_currency_is_dropped_rather_than_failing_the_payment() {
        when(metrics.addRevenue(eq(COURSE), any(), eq("EUR"), any(Instant.class))).thenReturn(0);
        when(metrics.existsById(COURSE)).thenReturn(true);

        assertThatCode(() -> analyticsService.on(new PaymentSucceeded(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), COURSE, new BigDecimal("40.00"), "EUR")))
                .doesNotThrowAnyException();
    }

    @Test
    void completion_rate_is_the_share_of_enrolled_learners_who_finished() {
        assertThat(CourseMetrics.empty(COURSE).completionRatePercent()).isZero();
    }

    @Test
    void a_course_with_no_activity_reports_zeroes_rather_than_failing() {
        when(metrics.findById(COURSE)).thenReturn(Optional.empty());

        var response = analyticsService.forCourse(COURSE);

        assertThat(response.enrollments()).isZero();
        assertThat(response.completionRatePercent()).isZero();
        assertThat(response.revenue()).isEqualByComparingTo("0");
    }
}

package com.lms.enrollment;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ValidationException;
import com.lms.enrollment.model.Enrollment;
import com.lms.enrollment.model.EnrollmentStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnrollmentTest {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();

    private static Enrollment free(int lessons) {
        return Enrollment.start(STUDENT, COURSE, lessons, false);
    }

    @Test
    void a_free_course_is_usable_straight_away() {
        Enrollment enrollment = free(3);

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(enrollment.hasAccess()).isTrue();
        assertThat(enrollment.getActivatedAt()).isNotNull();
    }

    @Test
    void a_paid_course_waits_for_payment_before_granting_access() {
        Enrollment enrollment = Enrollment.start(STUDENT, COURSE, 3, true);

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.PENDING_PAYMENT);
        assertThat(enrollment.hasAccess()).isFalse();
    }

    @Test
    void a_pending_enrollment_cannot_record_progress() {
        Enrollment enrollment = Enrollment.start(STUDENT, COURSE, 3, true);

        assertThatThrownBy(() -> enrollment.completeLesson(UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void payment_activates_the_enrollment() {
        Enrollment enrollment = Enrollment.start(STUDENT, COURSE, 3, true);

        enrollment.activate();

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(enrollment.getActivatedAt()).isNotNull();
    }

    @Test
    void activating_again_changes_nothing() {
        Enrollment enrollment = Enrollment.start(STUDENT, COURSE, 3, true);
        enrollment.activate();

        enrollment.activate();

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
    }

    @Test
    void a_cancelled_enrollment_is_never_silently_reactivated() {
        Enrollment enrollment = Enrollment.start(STUDENT, COURSE, 3, true);
        enrollment.cancel();

        assertThatThrownBy(enrollment::activate).isInstanceOf(ConflictException.class);
    }

    @Test
    void progress_is_reported_as_a_percentage() {
        Enrollment enrollment = free(4);
        enrollment.completeLesson(UUID.randomUUID());

        assertThat(enrollment.progressPercent()).isEqualTo(25);
    }

    @Test
    void finishing_the_same_lesson_twice_does_not_move_progress() {
        Enrollment enrollment = free(4);
        UUID lesson = UUID.randomUUID();

        assertThat(enrollment.completeLesson(lesson)).isTrue();
        assertThat(enrollment.completeLesson(lesson)).isFalse();
        assertThat(enrollment.progressPercent()).isEqualTo(25);
    }

    @Test
    void finishing_every_lesson_completes_the_enrollment() {
        Enrollment enrollment = free(2);
        enrollment.completeLesson(UUID.randomUUID());
        enrollment.completeLesson(UUID.randomUUID());

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.COMPLETED);
        assertThat(enrollment.progressPercent()).isEqualTo(100);
        assertThat(enrollment.getCompletedAt()).isNotNull();
    }

    @Test
    void adding_a_lesson_to_the_course_reopens_a_completed_enrollment() {
        Enrollment enrollment = free(1);
        enrollment.completeLesson(UUID.randomUUID());
        assertThat(enrollment.isComplete()).isTrue();

        enrollment.courseLessonCountChanged(2);

        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(enrollment.getCompletedAt()).isNull();
        assertThat(enrollment.progressPercent()).isEqualTo(50);
    }

    @Test
    void a_completed_enrollment_cannot_be_cancelled() {
        Enrollment enrollment = free(1);
        enrollment.completeLesson(UUID.randomUUID());

        assertThatThrownBy(enrollment::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void a_course_with_no_lessons_cannot_be_enrolled_in() {
        assertThatThrownBy(() -> Enrollment.start(STUDENT, COURSE, 0, false))
                .isInstanceOf(ValidationException.class);
    }
}

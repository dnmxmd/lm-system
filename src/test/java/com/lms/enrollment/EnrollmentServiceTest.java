package com.lms.enrollment;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ForbiddenException;
import com.lms.common.error.ValidationException;
import com.lms.common.access.PaymentLedger;
import com.lms.common.security.AuthenticatedUser;
import com.lms.common.security.Role;
import com.lms.course.service.CourseFacts;
import com.lms.course.service.CourseService;
import com.lms.enrollment.dto.EnrollmentResponse;
import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.enrollment.event.LessonCompleted;
import com.lms.enrollment.model.Enrollment;
import com.lms.enrollment.model.EnrollmentStatus;
import com.lms.enrollment.repository.EnrollmentRepository;
import com.lms.enrollment.service.EnrollmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID INSTRUCTOR = UUID.randomUUID();
    private static final AuthenticatedUser LEARNER =
            new AuthenticatedUser(STUDENT, "ann@example.com", Role.STUDENT);

    @Mock
    private EnrollmentRepository enrollments;
    @Mock
    private CourseService courses;
    @Mock
    private PaymentLedger payments;
    @Mock
    private ApplicationEventPublisher events;

    private EnrollmentService enrollmentService;

    @BeforeEach
    void setUp() {
        enrollmentService = new EnrollmentService(enrollments, courses, payments, events);
    }

    private static CourseFacts publishedCourse(boolean free, int lessons) {
        return facts(UUID.randomUUID(), true, free, lessonIds(lessons));
    }

    private static List<UUID> lessonIds(int count) {
        return IntStream.range(0, count).mapToObj(index -> UUID.randomUUID()).toList();
    }

    private static CourseFacts facts(UUID courseId, boolean open, boolean free, List<UUID> lessons) {
        return new CourseFacts(courseId, INSTRUCTOR, open, free,
                free ? BigDecimal.ZERO : new BigDecimal("49.99"), "USD", lessons);
    }

    @Test
    void a_free_course_is_active_immediately() {
        CourseFacts course = publishedCourse(true, 2);
        when(enrollments.findByStudentIdAndCourseId(STUDENT, course.courseId())).thenReturn(Optional.empty());
        when(courses.factsFor(course.courseId())).thenReturn(course);
        when(enrollments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EnrollmentResponse response = enrollmentService.enrol(STUDENT, course.courseId());

        assertThat(response.status()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(response.totalLessons()).isEqualTo(2);
    }

    @Test
    void a_paid_course_waits_for_payment_and_says_so_in_the_event() {
        CourseFacts course = publishedCourse(false, 2);
        when(enrollments.findByStudentIdAndCourseId(STUDENT, course.courseId())).thenReturn(Optional.empty());
        when(courses.factsFor(course.courseId())).thenReturn(course);
        when(enrollments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EnrollmentResponse response = enrollmentService.enrol(STUDENT, course.courseId());

        assertThat(response.status()).isEqualTo(EnrollmentStatus.PENDING_PAYMENT);
        ArgumentCaptor<EnrollmentCreated> event = ArgumentCaptor.forClass(EnrollmentCreated.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().requiresPayment()).isTrue();
        assertThat(event.getValue().price()).isEqualByComparingTo("49.99");
    }

    @Test
    void enrolling_twice_is_refused_before_the_catalog_is_even_read() {
        UUID courseId = UUID.randomUUID();
        when(enrollments.findByStudentIdAndCourseId(STUDENT, courseId))
                .thenReturn(Optional.of(Enrollment.start(STUDENT, courseId, 2, false)));

        assertThatThrownBy(() -> enrollmentService.enrol(STUDENT, courseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already enrolled");

        verify(courses, never()).factsFor(any());
        verify(enrollments, never()).save(any());
    }

    @Test
    void an_unpublished_course_cannot_be_enrolled_in() {
        CourseFacts draft = facts(UUID.randomUUID(), false, true, lessonIds(1));
        when(enrollments.findByStudentIdAndCourseId(STUDENT, draft.courseId())).thenReturn(Optional.empty());
        when(courses.factsFor(draft.courseId())).thenReturn(draft);

        assertThatThrownBy(() -> enrollmentService.enrol(STUDENT, draft.courseId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not open for enrolment");

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void finishing_a_lesson_records_progress_and_announces_it() {
        CourseFacts course = publishedCourse(true, 2);
        Enrollment enrollment = Enrollment.start(STUDENT, course.courseId(), 2, false);
        UUID firstLesson = course.lessonIds().get(0);
        when(enrollments.findWithProgressById(enrollment.getId())).thenReturn(Optional.of(enrollment));
        when(courses.factsFor(course.courseId())).thenReturn(course);

        EnrollmentResponse response =
                enrollmentService.completeLesson(enrollment.getId(), firstLesson, LEARNER);

        assertThat(response.progressPercent()).isEqualTo(50);
        verify(events).publishEvent(any(LessonCompleted.class));
        verify(events, never()).publishEvent(any(EnrollmentCompleted.class));
    }

    @Test
    void finishing_the_last_lesson_also_announces_completion() {
        CourseFacts course = publishedCourse(true, 2);
        List<UUID> lessons = course.lessonIds();
        Enrollment enrollment = Enrollment.start(STUDENT, course.courseId(), 2, false);
        enrollment.completeLesson(lessons.get(0));
        when(enrollments.findWithProgressById(enrollment.getId())).thenReturn(Optional.of(enrollment));
        when(courses.factsFor(course.courseId())).thenReturn(course);

        EnrollmentResponse response =
                enrollmentService.completeLesson(enrollment.getId(), lessons.get(1), LEARNER);

        assertThat(response.status()).isEqualTo(EnrollmentStatus.COMPLETED);
        verify(events).publishEvent(any(EnrollmentCompleted.class));
    }

    @Test
    void repeating_a_lesson_announces_nothing() {
        CourseFacts course = publishedCourse(true, 2);
        UUID firstLesson = course.lessonIds().get(0);
        Enrollment enrollment = Enrollment.start(STUDENT, course.courseId(), 2, false);
        enrollment.completeLesson(firstLesson);
        when(enrollments.findWithProgressById(enrollment.getId())).thenReturn(Optional.of(enrollment));
        when(courses.factsFor(course.courseId())).thenReturn(course);

        enrollmentService.completeLesson(enrollment.getId(), firstLesson, LEARNER);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void a_lesson_from_another_course_is_rejected() {
        CourseFacts course = publishedCourse(true, 2);
        Enrollment enrollment = Enrollment.start(STUDENT, course.courseId(), 2, false);
        when(enrollments.findWithProgressById(enrollment.getId())).thenReturn(Optional.of(enrollment));
        when(courses.factsFor(course.courseId())).thenReturn(course);

        assertThatThrownBy(() ->
                enrollmentService.completeLesson(enrollment.getId(), UUID.randomUUID(), LEARNER))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void another_learner_cannot_tick_off_your_lessons() {
        Enrollment enrollment = Enrollment.start(STUDENT, UUID.randomUUID(), 2, false);
        when(enrollments.findWithProgressById(enrollment.getId())).thenReturn(Optional.of(enrollment));
        AuthenticatedUser intruder = new AuthenticatedUser(UUID.randomUUID(), "bob@example.com", Role.STUDENT);

        assertThatThrownBy(() ->
                enrollmentService.completeLesson(enrollment.getId(), UUID.randomUUID(), intruder))
                .isInstanceOf(ForbiddenException.class);
    }
}

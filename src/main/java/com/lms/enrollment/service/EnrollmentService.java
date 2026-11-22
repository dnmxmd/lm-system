package com.lms.enrollment.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ForbiddenException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ValidationException;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.course.event.LessonRemoved;
import com.lms.course.service.CourseFacts;
import com.lms.course.service.CourseService;
import com.lms.enrollment.dto.EnrollmentResponse;
import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.enrollment.event.LessonCompleted;
import com.lms.enrollment.model.Enrollment;
import com.lms.enrollment.model.EnrollmentStatus;
import com.lms.enrollment.repository.EnrollmentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final CourseService courses;
    private final ApplicationEventPublisher events;

    public EnrollmentService(EnrollmentRepository enrollments, CourseService courses,
                             ApplicationEventPublisher events) {
        this.enrollments = enrollments;
        this.courses = courses;
        this.events = events;
    }

    @Transactional
    public EnrollmentResponse enrol(UUID studentId, UUID courseId) {
        Optional<Enrollment> existing = enrollments.findByStudentIdAndCourseId(studentId, courseId);
        if (existing.isPresent() && existing.get().getStatus() != EnrollmentStatus.CANCELLED) {
            throw new ConflictException("You are already enrolled in course " + courseId);
        }

        CourseFacts course = courses.factsFor(courseId);
        if (!course.openForEnrolment()) {
            throw new ConflictException("Course " + courseId + " is not open for enrolment");
        }

        // A free course is usable at once; a paid one stays pending until the payment settles.
        // Somebody who withdrew earlier starts the same row over rather than being turned away by
        // the one-enrolment-per-course rule.
        Enrollment enrollment = existing
                .map(cancelled -> {
                    cancelled.restart(course.lessonCount(), !course.free());
                    return cancelled;
                })
                .orElseGet(() -> enrollments.save(
                        Enrollment.start(studentId, courseId, course.lessonCount(), !course.free())));

        events.publishEvent(new EnrollmentCreated(enrollment.getId(), studentId, courseId,
                course.price(), course.currency(), !course.free()));
        return EnrollmentResponse.from(enrollment);
    }

    @Transactional
    public EnrollmentResponse completeLesson(UUID enrollmentId, UUID lessonId, AuthenticatedUser caller) {
        Enrollment enrollment = requireOwned(enrollmentId, caller);

        CourseFacts course = courses.factsFor(enrollment.getCourseId());
        if (!course.contains(lessonId)) {
            throw new ValidationException("Lesson " + lessonId + " is not part of this course");
        }
        // The instructor may have changed the lesson list since enrolment; reconcile before scoring.
        enrollment.reconcileWith(course.lessonIds());

        boolean firstTime = enrollment.completeLesson(lessonId);
        if (firstTime) {
            events.publishEvent(new LessonCompleted(enrollment.getId(), enrollment.getStudentId(),
                    enrollment.getCourseId(), lessonId, enrollment.progressPercent()));
            if (enrollment.isComplete()) {
                events.publishEvent(new EnrollmentCompleted(enrollment.getId(),
                        enrollment.getStudentId(), enrollment.getCourseId()));
            }
        }
        return EnrollmentResponse.from(enrollment);
    }

    /**
     * Takes back credit when a lesson leaves a course, for everyone enrolled in it.
     *
     * <p>Runs inside the instructor's transaction, so the course and the enrolments that depend on
     * it never disagree, even for a moment.
     * shortcut: loads every enrolment on the course. Fine for a class; batch it in SQL if a single
     * course ever carries tens of thousands of learners.
     */
    @EventListener
    @Transactional
    public void on(LessonRemoved event) {
        enrollments.findAllByCourseId(event.courseId())
                .forEach(enrollment ->
                        enrollment.lessonRemovedFromCourse(event.lessonId(), event.remainingLessons()));
    }

    /** Called when a payment settles. Idempotent, so a repeated confirmation changes nothing. */
    @Transactional
    public void activate(UUID enrollmentId) {
        require(enrollmentId).activate();
    }

    @Transactional
    public EnrollmentResponse cancel(UUID enrollmentId, AuthenticatedUser caller) {
        Enrollment enrollment = requireOwned(enrollmentId, caller);
        enrollment.cancel();
        return EnrollmentResponse.from(enrollment);
    }

    @Transactional(readOnly = true)
    public EnrollmentResponse byId(UUID enrollmentId, AuthenticatedUser caller) {
        return EnrollmentResponse.from(requireOwned(enrollmentId, caller));
    }

    @Transactional(readOnly = true)
    public PageResponse<EnrollmentResponse> forStudent(UUID studentId, PageQuery query) {
        var page = enrollments.findByStudentId(studentId,
                query.toPageable(Sort.by(Sort.Direction.DESC, "enrolledAt")));
        return PageResponse.of(page, EnrollmentResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<EnrollmentResponse> forCourse(UUID courseId, PageQuery query) {
        var page = enrollments.findByCourseId(courseId,
                query.toPageable(Sort.by(Sort.Direction.DESC, "enrolledAt")));
        return PageResponse.of(page, EnrollmentResponse::from);
    }

    private Enrollment require(UUID enrollmentId) {
        return enrollments.findWithProgressById(enrollmentId)
                .orElseThrow(() -> NotFoundException.of("Enrollment", enrollmentId));
    }

    private Enrollment requireOwned(UUID enrollmentId, AuthenticatedUser caller) {
        Enrollment enrollment = require(enrollmentId);
        if (!caller.isAdmin() && !enrollment.belongsTo(caller.id())) {
            throw new ForbiddenException("This enrolment belongs to another learner");
        }
        return enrollment;
    }
}

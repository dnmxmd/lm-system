package com.lms.enrollment.service;

import com.lms.common.access.CourseAccess;
import com.lms.enrollment.model.Enrollment;
import com.lms.enrollment.repository.EnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Answers the catalog's question about entitlement without the catalog having to know what an
 * enrolment is.
 */
@Service
class EnrolmentCourseAccess implements CourseAccess {

    private final EnrollmentRepository enrollments;

    EnrolmentCourseAccess(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    /** An enrolment still awaiting payment does not count: that is the whole point of paying. */
    @Override
    @Transactional(readOnly = true)
    public boolean maySeeContentOf(UUID courseId, UUID userId) {
        return enrollments.findByStudentIdAndCourseId(userId, courseId)
                .map(Enrollment::hasAccess)
                .orElse(false);
    }
}

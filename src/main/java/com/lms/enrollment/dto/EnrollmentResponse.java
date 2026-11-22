package com.lms.enrollment.dto;

import com.lms.enrollment.model.Enrollment;
import com.lms.enrollment.model.EnrollmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EnrollmentResponse(UUID id, UUID studentId, UUID courseId, EnrollmentStatus status,
                                 int totalLessons, int completedLessons, int progressPercent,
                                 List<UUID> completedLessonIds, Instant enrolledAt, Instant activatedAt,
                                 Instant completedAt, Instant lastActivityAt) {

    public static EnrollmentResponse from(Enrollment enrollment) {
        var completed = enrollment.getCompletedLessonIds();
        return new EnrollmentResponse(enrollment.getId(), enrollment.getStudentId(), enrollment.getCourseId(),
                enrollment.getStatus(), enrollment.getTotalLessons(), completed.size(),
                enrollment.progressPercent(), List.copyOf(completed), enrollment.getEnrolledAt(),
                enrollment.getActivatedAt(), enrollment.getCompletedAt(), enrollment.getLastActivityAt());
    }
}

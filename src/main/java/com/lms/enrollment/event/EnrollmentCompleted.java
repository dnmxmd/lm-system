package com.lms.enrollment.event;

import java.util.UUID;

public record EnrollmentCompleted(UUID enrollmentId, UUID studentId, UUID courseId) {
}

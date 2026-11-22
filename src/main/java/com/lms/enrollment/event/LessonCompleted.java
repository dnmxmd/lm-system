package com.lms.enrollment.event;

import java.util.UUID;

public record LessonCompleted(UUID enrollmentId, UUID studentId, UUID courseId,
                              UUID lessonId, int progressPercent) {
}

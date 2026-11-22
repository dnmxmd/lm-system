package com.lms.enrollment.event;

import java.math.BigDecimal;
import java.util.UUID;

public record EnrollmentCreated(UUID enrollmentId, UUID studentId, UUID courseId,
                                BigDecimal price, String currency, boolean requiresPayment) {
}

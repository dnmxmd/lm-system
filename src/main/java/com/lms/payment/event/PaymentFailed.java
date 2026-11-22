package com.lms.payment.event;

import java.util.UUID;

public record PaymentFailed(UUID paymentId, UUID enrollmentId, UUID studentId, UUID courseId, String reason) {
}

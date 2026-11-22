package com.lms.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentSucceeded(UUID paymentId, UUID enrollmentId, UUID studentId, UUID courseId,
                               BigDecimal amount, String currency) {
}

package com.lms.payment.dto;

import com.lms.payment.model.Payment;
import com.lms.payment.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, UUID enrollmentId, UUID studentId, UUID courseId,
                              BigDecimal amount, String currency, PaymentStatus status,
                              String providerReference, String failureReason,
                              Instant createdAt, Instant settledAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getEnrollmentId(), payment.getStudentId(),
                payment.getCourseId(), payment.getAmount(), payment.getCurrency(), payment.getStatus(),
                payment.getProviderReference(), payment.getFailureReason(), payment.getCreatedAt(),
                payment.getSettledAt());
    }
}

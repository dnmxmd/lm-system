package com.lms.payment.service;

import com.lms.common.access.PaymentLedger;
import com.lms.payment.model.PaymentStatus;
import com.lms.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Answers enrolment's question about whether a course has been paid for, without enrolment having
 * to know what a payment is.
 */
@Service
class EnrolmentPaymentLedger implements PaymentLedger {

    private final PaymentRepository payments;

    EnrolmentPaymentLedger(PaymentRepository payments) {
        this.payments = payments;
    }

    /**
     * A refunded payment does not count. The money went back, so rejoining has to be paid for
     * again — while the refunded row stays on the record.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasSettledPayment(UUID enrollmentId) {
        return payments.existsByEnrollmentIdAndStatus(enrollmentId, PaymentStatus.SUCCEEDED);
    }
}

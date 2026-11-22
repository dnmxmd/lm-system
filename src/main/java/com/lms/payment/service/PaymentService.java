package com.lms.payment.service;

import com.lms.common.error.ForbiddenException;
import com.lms.common.error.NotFoundException;
import com.lms.common.money.Money;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.enrollment.service.EnrollmentService;
import com.lms.payment.dto.PaymentResponse;
import com.lms.payment.event.PaymentFailed;
import com.lms.payment.event.PaymentSucceeded;
import com.lms.payment.model.Payment;
import com.lms.payment.model.PaymentStatus;
import com.lms.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final EnrollmentService enrollments;
    private final ApplicationEventPublisher events;

    public PaymentService(PaymentRepository payments, EnrollmentService enrollments,
                          ApplicationEventPublisher events) {
        this.payments = payments;
        this.enrollments = enrollments;
        this.events = events;
    }

    /**
     * Raises the charge as soon as somebody enrols in a paid course, so the client never has to be
     * told an amount and then trusted to send it back.
     *
     * <p>Runs inside the enrolment's own transaction, not after it. An after-commit listener in a
     * new transaction needs a second database connection while the first is still held, which
     * doubles the connection demand of every enrolment and starves the pool as soon as a course
     * gets busy. Sharing the transaction also makes the pair atomic: there is no such thing as an
     * enrolment awaiting a payment that was never raised.
     */
    @EventListener
    @Transactional
    public void onEnrollmentCreated(EnrollmentCreated event) {
        if (!event.requiresPayment()) {
            return;
        }
        payments.findByEnrollmentId(event.enrollmentId()).ifPresentOrElse(
                existing -> {
                    // The learner withdrew and came back. Their old charge is reusable unless it
                    // already went through.
                    if (existing.getStatus() == PaymentStatus.FAILED) {
                        existing.retry();
                    }
                    log.debug("Payment {} already exists for enrollment {}",
                            existing.getId(), event.enrollmentId());
                },
                () -> {
                    Payment payment = payments.save(Payment.initiate(event.enrollmentId(), event.studentId(),
                            event.courseId(), Money.of(event.price(), event.currency())));
                    log.info("Payment {} is due for enrollment {}", payment.getId(), event.enrollmentId());
                });
    }

    /**
     * Settles the payment and lets the learner in, in one transaction: there is no window in which
     * the money has been taken but access has not been granted.
     */
    @Transactional
    public PaymentResponse confirm(UUID paymentId, String providerReference, AuthenticatedUser caller) {
        Payment payment = requireOwned(paymentId, caller);
        payment.succeed(providerReference);
        enrollments.activate(payment.getEnrollmentId());

        events.publishEvent(new PaymentSucceeded(payment.getId(), payment.getEnrollmentId(),
                payment.getStudentId(), payment.getCourseId(), payment.getAmount(), payment.getCurrency()));
        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse fail(UUID paymentId, String reason, AuthenticatedUser caller) {
        Payment payment = requireOwned(paymentId, caller);
        payment.fail(reason);

        events.publishEvent(new PaymentFailed(payment.getId(), payment.getEnrollmentId(),
                payment.getStudentId(), payment.getCourseId(), payment.getFailureReason()));
        return PaymentResponse.from(payment);
    }

    /** Lets a learner whose card was declined try again, rather than being locked out for good. */
    @Transactional
    public PaymentResponse retry(UUID paymentId, AuthenticatedUser caller) {
        Payment payment = requireOwned(paymentId, caller);
        payment.retry();
        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse refund(UUID paymentId) {
        Payment payment = require(paymentId);
        payment.refund();
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse byId(UUID paymentId, AuthenticatedUser caller) {
        return PaymentResponse.from(requireOwned(paymentId, caller));
    }

    @Transactional(readOnly = true)
    public PaymentResponse forEnrollment(UUID enrollmentId, AuthenticatedUser caller) {
        Payment payment = payments.findByEnrollmentId(enrollmentId)
                .orElseThrow(() -> new NotFoundException("No payment is due for enrollment " + enrollmentId));
        requireOwnership(payment, caller);
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> forStudent(UUID studentId, PageQuery query) {
        var page = payments.findByStudentId(studentId,
                query.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(page, PaymentResponse::from);
    }

    private Payment require(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> NotFoundException.of("Payment", paymentId));
    }

    private Payment requireOwned(UUID paymentId, AuthenticatedUser caller) {
        Payment payment = require(paymentId);
        requireOwnership(payment, caller);
        return payment;
    }

    private static void requireOwnership(Payment payment, AuthenticatedUser caller) {
        if (!caller.isAdmin() && !payment.belongsTo(caller.id())) {
            throw new ForbiddenException("This payment belongs to another learner");
        }
    }
}

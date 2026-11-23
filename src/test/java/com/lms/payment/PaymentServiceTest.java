package com.lms.payment;

import com.lms.common.error.ForbiddenException;
import com.lms.common.error.NotFoundException;
import com.lms.common.money.Money;
import com.lms.common.security.AuthenticatedUser;
import com.lms.common.security.Role;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.enrollment.service.EnrollmentService;
import com.lms.payment.dto.PaymentResponse;
import com.lms.payment.event.PaymentSucceeded;
import com.lms.payment.model.Payment;
import com.lms.payment.model.PaymentStatus;
import com.lms.payment.repository.PaymentRepository;
import com.lms.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final AuthenticatedUser LEARNER =
            new AuthenticatedUser(STUDENT, "ann@example.com", Role.STUDENT);

    @Mock
    private PaymentRepository payments;
    @Mock
    private EnrollmentService enrollments;
    @Mock
    private ApplicationEventPublisher events;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(payments, enrollments, events);
    }

    private static Payment pending() {
        return Payment.initiate(UUID.randomUUID(), STUDENT, COURSE, Money.of(new BigDecimal("49.99"), "USD"));
    }

    @Test
    void a_paid_enrollment_raises_a_charge() {
        UUID enrollmentId = UUID.randomUUID();
        when(payments.findTopByEnrollmentIdOrderByCreatedAtDescIdDesc(enrollmentId)).thenReturn(Optional.empty());
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        paymentService.onEnrollmentCreated(new EnrollmentCreated(enrollmentId, STUDENT, COURSE,
                new BigDecimal("49.99"), "USD", true));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(saved.capture());
        assertThat(saved.getValue().getEnrollmentId()).isEqualTo(enrollmentId);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("49.99");
    }

    @Test
    void a_free_enrollment_raises_nothing() {
        paymentService.onEnrollmentCreated(new EnrollmentCreated(UUID.randomUUID(), STUDENT, COURSE,
                BigDecimal.ZERO, "USD", false));

        verify(payments, never()).save(any());
    }

    @Test
    void an_enrollment_that_already_has_a_charge_does_not_get_a_second_one() {
        UUID enrollmentId = UUID.randomUUID();
        when(payments.findTopByEnrollmentIdOrderByCreatedAtDescIdDesc(enrollmentId)).thenReturn(Optional.of(pending()));

        paymentService.onEnrollmentCreated(new EnrollmentCreated(enrollmentId, STUDENT, COURSE,
                new BigDecimal("49.99"), "USD", true));

        verify(payments, never()).save(any());
    }

    @Test
    void settling_a_payment_lets_the_learner_in() {
        Payment payment = pending();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.confirm(payment.getId(), "ch_1234567890", LEARNER);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(enrollments).activate(payment.getEnrollmentId());
        ArgumentCaptor<PaymentSucceeded> event = ArgumentCaptor.forClass(PaymentSucceeded.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().amount()).isEqualByComparingTo("49.99");
    }

    @Test
    void somebody_else_cannot_settle_your_payment() {
        Payment payment = pending();
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        AuthenticatedUser intruder = new AuthenticatedUser(UUID.randomUUID(), "bob@example.com", Role.STUDENT);

        assertThatThrownBy(() -> paymentService.confirm(payment.getId(), "ch_x", intruder))
                .isInstanceOf(ForbiddenException.class);

        verify(enrollments, never()).activate(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void an_unknown_payment_is_a_not_found() {
        UUID missing = UUID.randomUUID();
        when(payments.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.confirm(missing, "ch_x", LEARNER))
                .isInstanceOf(NotFoundException.class);
    }
}

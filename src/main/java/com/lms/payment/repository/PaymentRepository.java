package com.lms.payment.repository;

import com.lms.payment.model.Payment;
import com.lms.payment.model.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * The current charge for an enrolment. An enrolment can carry several over its life — a
     * refunded one and the replacement bought on rejoining — so "the payment" means the newest.
     */
    Optional<Payment> findTopByEnrollmentIdOrderByCreatedAtDescIdDesc(UUID enrollmentId);

    boolean existsByEnrollmentIdAndStatus(UUID enrollmentId, PaymentStatus status);

    Page<Payment> findByStudentId(UUID studentId, Pageable pageable);
}

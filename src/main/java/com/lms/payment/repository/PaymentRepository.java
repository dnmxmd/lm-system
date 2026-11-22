package com.lms.payment.repository;

import com.lms.payment.model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByEnrollmentId(UUID enrollmentId);

    Page<Payment> findByStudentId(UUID studentId, Pageable pageable);
}

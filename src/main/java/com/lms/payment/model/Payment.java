package com.lms.payment.model;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ValidationException;
import com.lms.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * One charge for one enrolment.
 *
 * <p>The state machine is the point of this class: {@code PENDING} may become {@code SUCCEEDED} or
 * {@code FAILED}, and only a succeeded payment can be refunded. Everything else is refused, so a
 * duplicate callback from the provider cannot charge or refund twice.
 */
@Entity
@Table(name = "payments")
public class Payment {

    private static final int MAX_REASON_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "enrollment_id", nullable = false, unique = true)
    private UUID enrollmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    private long version;

    protected Payment() {
        // for JPA
    }

    private Payment(UUID enrollmentId, UUID studentId, UUID courseId, Money price) {
        if (price.isZero()) {
            throw new ValidationException("A free enrolment does not need a payment");
        }
        this.id = UUID.randomUUID();
        this.enrollmentId = Objects.requireNonNull(enrollmentId, "enrollmentId is required");
        this.studentId = Objects.requireNonNull(studentId, "studentId is required");
        this.courseId = Objects.requireNonNull(courseId, "courseId is required");
        this.amount = price.amount();
        this.currency = price.currencyCode();
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static Payment initiate(UUID enrollmentId, UUID studentId, UUID courseId, Money price) {
        return new Payment(enrollmentId, studentId, courseId, price);
    }

    public void succeed(String reference) {
        requirePending("confirmed");
        if (reference == null || reference.isBlank()) {
            throw new ValidationException("A provider reference is required to confirm a payment");
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.providerReference = reference.trim();
        this.settledAt = Instant.now();
    }

    public void fail(String reason) {
        requirePending("failed");
        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(reason);
        this.settledAt = Instant.now();
    }

    /**
     * Puts a declined payment back in play.
     *
     * <p>Without this a single decline is terminal: the learner cannot confirm the failed payment,
     * cannot raise another one, and cannot enrol again because they already are. The notification
     * sent on failure invites them to try again, so something has to let them.
     */
    public void retry() {
        if (status != PaymentStatus.FAILED) {
            throw new ConflictException("Only a failed payment can be retried; this one is " + status);
        }
        this.status = PaymentStatus.PENDING;
        this.failureReason = null;
        this.settledAt = null;
    }

    public void refund() {
        if (status != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("Only a succeeded payment can be refunded; this one is " + status);
        }
        this.status = PaymentStatus.REFUNDED;
        this.settledAt = Instant.now();
    }

    public boolean isSettled() {
        return status == PaymentStatus.SUCCEEDED;
    }

    public boolean belongsTo(UUID candidateStudentId) {
        return studentId.equals(candidateStudentId);
    }

    public Money getPrice() {
        return new Money(amount, Currency.getInstance(currency));
    }

    private void requirePending(String action) {
        if (status != PaymentStatus.PENDING) {
            throw new ConflictException("Payment " + id + " is " + status + " and cannot be " + action);
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Declined by the provider";
        }
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Payment payment && id != null && id.equals(payment.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}

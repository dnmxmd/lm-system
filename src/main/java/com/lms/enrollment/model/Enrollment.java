package com.lms.enrollment.model;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ValidationException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One learner's relationship with one course: whether they are in, and how far they have got.
 *
 * <p>Progress is the set of finished lesson ids rather than a counter, so finishing the same lesson
 * twice cannot inflate it and lessons may be done in any order.
 */
@Entity
@Table(name = "enrollments",
        uniqueConstraints = @UniqueConstraint(name = "uq_enrollments_student_course",
                columnNames = {"student_id", "course_id"}))
public class Enrollment {

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "total_lessons", nullable = false)
    private int totalLessons;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EnrollmentStatus status;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "enrollment_completed_lessons",
            joinColumns = @JoinColumn(name = "enrollment_id"))
    @Column(name = "lesson_id", nullable = false)
    @BatchSize(size = 50)
    private final Set<UUID> completedLessonIds = new HashSet<>();

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Version
    private long version;

    protected Enrollment() {
        // for JPA
    }

    private Enrollment(UUID studentId, UUID courseId, int totalLessons, boolean requiresPayment) {
        if (totalLessons < 1) {
            throw new ValidationException("A course with no lessons cannot be enrolled in");
        }
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.studentId = Objects.requireNonNull(studentId, "studentId is required");
        this.courseId = Objects.requireNonNull(courseId, "courseId is required");
        this.totalLessons = totalLessons;
        this.status = requiresPayment ? EnrollmentStatus.PENDING_PAYMENT : EnrollmentStatus.ACTIVE;
        this.enrolledAt = now;
        this.activatedAt = requiresPayment ? null : now;
        this.lastActivityAt = now;
    }

    /**
     * Starts an enrolment. A free course is usable immediately; a paid one waits for payment,
     * which keeps the money decision out of this class.
     */
    public static Enrollment start(UUID studentId, UUID courseId, int totalLessons, boolean requiresPayment) {
        return new Enrollment(studentId, courseId, totalLessons, requiresPayment);
    }

    /** Grants access once payment has settled. Already-active enrolments are left alone. */
    public void activate() {
        if (status == EnrollmentStatus.ACTIVE || status == EnrollmentStatus.COMPLETED) {
            return;
        }
        if (status == EnrollmentStatus.CANCELLED) {
            throw new ConflictException("Enrollment " + id + " was cancelled and cannot be activated");
        }
        this.status = EnrollmentStatus.ACTIVE;
        this.activatedAt = Instant.now();
        touch();
    }

    /** @return true when this lesson had not been finished before, false when it is a repeat. */
    public boolean completeLesson(UUID lessonId) {
        Objects.requireNonNull(lessonId, "lessonId is required");
        if (status != EnrollmentStatus.ACTIVE) {
            throw new ConflictException("Enrollment " + id + " is " + status + " and cannot record progress");
        }
        if (!completedLessonIds.add(lessonId)) {
            return false;
        }
        touch();
        if (completedLessonIds.size() >= totalLessons) {
            this.status = EnrollmentStatus.COMPLETED;
            this.completedAt = Instant.now();
        }
        return true;
    }

    /**
     * Starts a cancelled enrolment over, keeping the same row so the one-per-learner-per-course
     * rule in the database still holds. Progress is dropped: they are beginning again.
     */
    public void restart(int totalLessons, boolean requiresPayment) {
        if (status != EnrollmentStatus.CANCELLED) {
            throw new ConflictException("Enrollment " + id + " is " + status + " and is not cancelled");
        }
        if (totalLessons < 1) {
            throw new ValidationException("A course with no lessons cannot be enrolled in");
        }
        this.totalLessons = totalLessons;
        this.completedLessonIds.clear();
        this.completedAt = null;
        this.status = requiresPayment ? EnrollmentStatus.PENDING_PAYMENT : EnrollmentStatus.ACTIVE;
        this.activatedAt = requiresPayment ? null : Instant.now();
        touch();
    }

    public void cancel() {
        if (status == EnrollmentStatus.COMPLETED) {
            throw new ConflictException("Enrollment " + id + " is already completed and cannot be cancelled");
        }
        if (status == EnrollmentStatus.CANCELLED) {
            throw new ConflictException("Enrollment " + id + " is already cancelled");
        }
        this.status = EnrollmentStatus.CANCELLED;
        touch();
    }

    /**
     * Brings progress back in line with the course as it stands now.
     *
     * <p>Takes the actual lesson ids rather than a count: credit has to be dropped for lessons the
     * course no longer contains, and a count alone cannot tell which those are.
     */
    public void reconcileWith(Collection<UUID> currentLessonIds) {
        if (completedLessonIds.retainAll(Set.copyOf(currentLessonIds))) {
            touch();
        }
        applyLessonCount(currentLessonIds.size());
    }

    /** Keeps progress honest when the instructor adds or removes lessons after people enrolled. */
    public void courseLessonCountChanged(int newTotal) {
        applyLessonCount(newTotal);
    }

    /**
     * Takes back credit for a lesson the course no longer contains.
     *
     * <p>Without this the finished-lesson id would sit in the set forever and keep counting, so
     * trimming a course could hand a learner a completion for work that is no longer in it.
     */
    public void lessonRemovedFromCourse(UUID lessonId, int remainingLessons) {
        if (completedLessonIds.remove(lessonId)) {
            touch();
        }
        applyLessonCount(remainingLessons);
    }

    /**
     * Re-derives the status from the progress. It moves in both directions: a course that grew
     * reopens a completed enrolment, and one that shrank can finish an active one.
     * A pending or cancelled enrolment is left alone — its status is not about progress.
     */
    private void applyLessonCount(int newTotal) {
        if (newTotal < 1) {
            throw new ValidationException("A course must keep at least one lesson");
        }
        this.totalLessons = newTotal;
        if (status == EnrollmentStatus.COMPLETED && completedLessonIds.size() < newTotal) {
            this.status = EnrollmentStatus.ACTIVE;
            this.completedAt = null;
            touch();
        } else if (status == EnrollmentStatus.ACTIVE && completedLessonIds.size() >= newTotal) {
            this.status = EnrollmentStatus.COMPLETED;
            this.completedAt = Instant.now();
            touch();
        }
    }

    public int progressPercent() {
        if (totalLessons == 0) {
            return 0;
        }
        return Math.min(100, (int) Math.round(completedLessonIds.size() * 100.0 / totalLessons));
    }

    public boolean isComplete() {
        return status == EnrollmentStatus.COMPLETED;
    }

    public boolean hasAccess() {
        return status == EnrollmentStatus.ACTIVE || status == EnrollmentStatus.COMPLETED;
    }

    public boolean belongsTo(UUID candidateStudentId) {
        return studentId.equals(candidateStudentId);
    }

    private void touch() {
        this.lastActivityAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public int getTotalLessons() {
        return totalLessons;
    }

    public EnrollmentStatus getStatus() {
        return status;
    }

    public Set<UUID> getCompletedLessonIds() {
        return Collections.unmodifiableSet(completedLessonIds);
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Enrollment enrollment && id != null && id.equals(enrollment.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}

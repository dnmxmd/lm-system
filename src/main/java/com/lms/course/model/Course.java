package com.lms.course.model;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ForbiddenException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ValidationException;
import com.lms.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A course and its lessons, as one unit.
 *
 * <p>Lessons are reached only through the course, which is what lets it guarantee that positions
 * stay contiguous and that a published course always has something to learn.
 */
@Entity
@Table(name = "courses")
public class Course {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_LESSONS = 500;

    @Id
    private UUID id;

    @Column(name = "instructor_id", nullable = false)
    private UUID instructorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(length = 2000)
    private String summary;

    @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", nullable = false, length = 3)
    private String priceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    private long version;

    /**
     * Batched rather than join-fetched: a catalog page loads 20 courses and then their lessons in
     * one extra query, instead of multiplying rows and paginating in memory.
     */
    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    @BatchSize(size = 50)
    private final List<Lesson> lessons = new ArrayList<>();

    protected Course() {
        // for JPA
    }

    private Course(UUID instructorId, String title, String summary, Money price, CourseLevel level) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.instructorId = Objects.requireNonNull(instructorId, "instructorId is required");
        this.title = validTitle(title);
        this.slug = Slug.from(title);
        this.summary = validSummary(summary);
        setPrice(Objects.requireNonNull(price, "price is required"));
        this.level = level == null ? CourseLevel.BEGINNER : level;
        this.status = CourseStatus.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Course draft(UUID instructorId, String title, String summary, Money price, CourseLevel level) {
        return new Course(instructorId, title, summary, price, level);
    }

    public Lesson addLesson(String lessonTitle, int durationMinutes, String contentUrl) {
        requireEditable();
        if (lessons.size() >= MAX_LESSONS) {
            throw new ValidationException("A course cannot hold more than " + MAX_LESSONS + " lessons");
        }
        Lesson lesson = new Lesson(this, lessonTitle, lessons.size() + 1, durationMinutes, contentUrl);
        lessons.add(lesson);
        touch();
        return lesson;
    }

    /** Removes a lesson and closes the gap, so positions stay 1..n with no holes. */
    public void removeLesson(UUID lessonId) {
        requireEditable();
        boolean removed = lessons.removeIf(lesson -> lesson.getId().equals(lessonId));
        if (!removed) {
            throw NotFoundException.of("Lesson", lessonId);
        }
        for (int index = 0; index < lessons.size(); index++) {
            lessons.get(index).moveTo(index + 1);
        }
        touch();
    }

    public void rename(String newTitle) {
        requireEditable();
        this.title = validTitle(newTitle);
        // The slug is part of a published URL, so it is only re-derived while the course is a draft.
        if (status == CourseStatus.DRAFT) {
            this.slug = Slug.from(newTitle);
        }
        touch();
    }

    public void describe(String newSummary) {
        requireEditable();
        this.summary = validSummary(newSummary);
        touch();
    }

    public void reprice(Money newPrice) {
        requireEditable();
        setPrice(Objects.requireNonNull(newPrice, "price is required"));
        touch();
    }

    public void publish() {
        if (status == CourseStatus.PUBLISHED) {
            throw new ConflictException("Course " + id + " is already published");
        }
        if (status == CourseStatus.ARCHIVED) {
            throw new ConflictException("Course " + id + " is archived and cannot be published again");
        }
        if (lessons.isEmpty()) {
            throw new ValidationException("A course needs at least one lesson before it can be published");
        }
        if (summary == null || summary.isBlank()) {
            throw new ValidationException("A course needs a summary before it can be published");
        }
        this.status = CourseStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        touch();
    }

    public void archive() {
        if (status == CourseStatus.ARCHIVED) {
            throw new ConflictException("Course " + id + " is already archived");
        }
        this.status = CourseStatus.ARCHIVED;
        touch();
    }

    /** Guards every write: a course belongs to the instructor who created it. */
    public void requireOwnedBy(UUID callerId, boolean callerIsAdmin) {
        if (!callerIsAdmin && !instructorId.equals(callerId)) {
            throw new ForbiddenException("Course " + id + " belongs to another instructor");
        }
    }

    public boolean isOpenForEnrolment() {
        return status == CourseStatus.PUBLISHED;
    }

    public boolean isFree() {
        return priceAmount.signum() == 0;
    }

    public Money getPrice() {
        return new Money(priceAmount, Currency.getInstance(priceCurrency));
    }

    public int totalDurationMinutes() {
        return lessons.stream().mapToInt(Lesson::getDurationMinutes).sum();
    }

    public List<UUID> lessonIds() {
        return lessons.stream().map(Lesson::getId).toList();
    }

    public boolean hasLesson(UUID lessonId) {
        return lessons.stream().anyMatch(lesson -> lesson.getId().equals(lessonId));
    }

    private void setPrice(Money price) {
        this.priceAmount = price.amount();
        this.priceCurrency = price.currencyCode();
    }

    private void requireEditable() {
        if (status == CourseStatus.ARCHIVED) {
            throw new ConflictException("Course " + id + " is archived and can no longer be changed");
        }
    }

    private static String validTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ValidationException("Course title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("Course title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String validSummary(String summary) {
        if (summary != null && summary.length() > MAX_SUMMARY_LENGTH) {
            throw new ValidationException("Course summary must be at most "
                    + MAX_SUMMARY_LENGTH + " characters");
        }
        return summary == null ? null : summary.trim();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstructorId() {
        return instructorId;
    }

    public String getTitle() {
        return title;
    }

    public String getSlug() {
        return slug;
    }

    public String getSummary() {
        return summary;
    }

    public CourseLevel getLevel() {
        return level;
    }

    public CourseStatus getStatus() {
        return status;
    }

    public List<Lesson> getLessons() {
        return Collections.unmodifiableList(lessons);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Course course && id != null && id.equals(course.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}

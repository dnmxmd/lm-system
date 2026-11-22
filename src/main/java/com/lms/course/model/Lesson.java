package com.lms.course.model;

import com.lms.common.error.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A single unit of a course. Lessons belong to their course: they are created, reordered and
 * removed only through it, which is how positions stay contiguous.
 */
@Entity
@Table(name = "lessons")
public class Lesson {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DURATION_MINUTES = 60 * 24;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private int position;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "content_url", length = 2048)
    private String contentUrl;

    protected Lesson() {
        // for JPA
    }

    Lesson(Course course, String title, int position, int durationMinutes, String contentUrl) {
        if (title == null || title.isBlank()) {
            throw new ValidationException("Lesson title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("Lesson title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        if (durationMinutes <= 0 || durationMinutes > MAX_DURATION_MINUTES) {
            throw new ValidationException("Lesson duration must be between 1 and "
                    + MAX_DURATION_MINUTES + " minutes");
        }
        this.id = UUID.randomUUID();
        this.course = course;
        this.title = trimmed;
        this.position = position;
        this.durationMinutes = durationMinutes;
        this.contentUrl = contentUrl;
    }

    void moveTo(int newPosition) {
        this.position = newPosition;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public int getPosition() {
        return position;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getContentUrl() {
        return contentUrl;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Lesson lesson && id != null && id.equals(lesson.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}

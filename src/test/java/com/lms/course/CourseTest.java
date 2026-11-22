package com.lms.course;

import com.lms.common.error.ConflictException;
import com.lms.common.error.ForbiddenException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ValidationException;
import com.lms.common.money.Money;
import com.lms.course.model.Course;
import com.lms.course.model.CourseLevel;
import com.lms.course.model.CourseStatus;
import com.lms.course.model.Lesson;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseTest {

    private static final UUID INSTRUCTOR = UUID.randomUUID();

    private static Course draft() {
        return Course.draft(INSTRUCTOR, "Clean Architecture in Java", "Layers that hold up",
                Money.of(new BigDecimal("49.99"), "USD"), CourseLevel.INTERMEDIATE);
    }

    @Test
    void a_new_course_starts_as_a_draft_and_is_not_enrollable() {
        Course course = draft();

        assertThat(course.getStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(course.isOpenForEnrolment()).isFalse();
        assertThat(course.getSlug()).isEqualTo("clean-architecture-in-java");
    }

    @Test
    void refuses_to_publish_a_course_with_no_lessons() {
        assertThatThrownBy(draft()::publish)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one lesson");
    }

    @Test
    void refuses_to_publish_a_course_with_no_summary() {
        Course course = Course.draft(INSTRUCTOR, "Untitled work", null, Money.zero("USD"), null);
        course.addLesson("First", 10, null);

        assertThatThrownBy(course::publish)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void publishing_opens_enrolment_and_stamps_the_time() {
        Course course = draft();
        course.addLesson("Why layers", 12, "https://cdn.example.com/1");

        course.publish();

        assertThat(course.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(course.isOpenForEnrolment()).isTrue();
        assertThat(course.getPublishedAt()).isNotNull();
    }

    @Test
    void publishing_twice_is_a_conflict() {
        Course course = draft();
        course.addLesson("Why layers", 12, null);
        course.publish();

        assertThatThrownBy(course::publish).isInstanceOf(ConflictException.class);
    }

    @Test
    void lessons_are_numbered_from_one_in_the_order_they_are_added() {
        Course course = draft();
        course.addLesson("First", 10, null);
        course.addLesson("Second", 20, null);
        course.addLesson("Third", 30, null);

        assertThat(course.getLessons()).extracting(Lesson::getPosition).containsExactly(1, 2, 3);
        assertThat(course.totalDurationMinutes()).isEqualTo(60);
    }

    @Test
    void removing_a_lesson_closes_the_gap_in_the_numbering() {
        Course course = draft();
        course.addLesson("First", 10, null);
        Lesson second = course.addLesson("Second", 20, null);
        course.addLesson("Third", 30, null);

        course.removeLesson(second.getId());

        assertThat(course.getLessons()).extracting(Lesson::getTitle).containsExactly("First", "Third");
        assertThat(course.getLessons()).extracting(Lesson::getPosition).containsExactly(1, 2);
    }

    @Test
    void removing_a_lesson_that_is_not_there_is_reported_rather_than_ignored() {
        Course course = draft();

        assertThatThrownBy(() -> course.removeLesson(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void a_lesson_must_have_a_sensible_duration() {
        Course course = draft();

        assertThatThrownBy(() -> course.addLesson("Zero length", 0, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void an_archived_course_can_no_longer_be_edited_or_published() {
        Course course = draft();
        course.addLesson("First", 10, null);
        course.archive();

        assertThatThrownBy(() -> course.addLesson("Late addition", 10, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(course::publish).isInstanceOf(ConflictException.class);
    }

    @Test
    void only_the_owning_instructor_or_an_admin_may_change_a_course() {
        Course course = draft();
        UUID somebodyElse = UUID.randomUUID();

        assertThatThrownBy(() -> course.requireOwnedBy(somebodyElse, false))
                .isInstanceOf(ForbiddenException.class);
        course.requireOwnedBy(somebodyElse, true);
        course.requireOwnedBy(INSTRUCTOR, false);
    }

    @Test
    void renaming_a_published_course_keeps_its_public_link() {
        Course course = draft();
        course.addLesson("First", 10, null);
        course.publish();

        course.rename("Clean Architecture, Revised");

        assertThat(course.getTitle()).isEqualTo("Clean Architecture, Revised");
        assertThat(course.getSlug()).isEqualTo("clean-architecture-in-java");
    }

    @Test
    void renaming_a_draft_updates_its_link() {
        Course course = draft();

        course.rename("Hexagonal Architecture");

        assertThat(course.getSlug()).isEqualTo("hexagonal-architecture");
    }

    @Test
    void a_zero_price_course_is_free() {
        Course free = Course.draft(INSTRUCTOR, "Intro to Java", "Free sampler",
                Money.zero("USD"), CourseLevel.BEGINNER);

        assertThat(free.isFree()).isTrue();
    }
}

package com.lms.course.repository;

import com.lms.course.model.Course;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface CourseRepository extends JpaRepository<Course, UUID>, JpaSpecificationExecutor<Course> {

    @EntityGraph(attributePaths = "lessons")
    Optional<Course> findWithLessonsById(UUID id);

    @EntityGraph(attributePaths = "lessons")
    Optional<Course> findWithLessonsBySlug(String slug);

    boolean existsBySlug(String slug);
}

package com.lms.enrollment.repository;

import com.lms.enrollment.model.Enrollment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    @EntityGraph(attributePaths = "completedLessonIds")
    Optional<Enrollment> findWithProgressById(UUID id);

    boolean existsByStudentIdAndCourseId(UUID studentId, UUID courseId);

    @EntityGraph(attributePaths = "completedLessonIds")
    Optional<Enrollment> findByStudentIdAndCourseId(UUID studentId, UUID courseId);

    @EntityGraph(attributePaths = "completedLessonIds")
    Page<Enrollment> findByStudentId(UUID studentId, Pageable pageable);

    @EntityGraph(attributePaths = "completedLessonIds")
    Page<Enrollment> findByCourseId(UUID courseId, Pageable pageable);

    @EntityGraph(attributePaths = "completedLessonIds")
    List<Enrollment> findAllByCourseId(UUID courseId);
}

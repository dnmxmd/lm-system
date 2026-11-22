package com.lms.course.service;

import com.lms.common.error.NotFoundException;
import com.lms.course.dto.CourseResponse;
import com.lms.course.model.Slug;
import com.lms.course.repository.CourseRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The public, cacheable view of a course: everything except what people are paying for.
 *
 * <p>A separate bean rather than a method on {@link CourseService} because Spring's caching works
 * through a proxy — calling a {@code @Cacheable} method from inside the same class would quietly
 * skip the cache.
 *
 * <p>Only the syllabus is cached. The entitled view varies by who is asking, so caching it under a
 * course-only key would hand one learner's access to everybody.
 */
@Service
public class CourseCatalogReader {

    public static final String CACHE = "courses";

    private final CourseRepository courses;

    public CourseCatalogReader(CourseRepository courses) {
        this.courses = courses;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE, key = "#courseId")
    public CourseResponse syllabusById(UUID courseId) {
        return courses.findWithLessonsById(courseId)
                .map(CourseResponse::syllabus)
                .orElseThrow(() -> NotFoundException.of("Course", courseId));
    }

    @Transactional(readOnly = true)
    public CourseResponse syllabusBySlug(String slug) {
        return courses.findWithLessonsBySlug(Slug.requireValid(slug))
                .map(CourseResponse::syllabus)
                .orElseThrow(() -> new NotFoundException("No course is published at /" + slug));
    }
}

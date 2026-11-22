package com.lms.course.service;

import com.lms.common.access.CourseAccess;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.money.Money;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.course.dto.AddLessonRequest;
import com.lms.course.dto.CourseResponse;
import com.lms.course.dto.CourseSummaryResponse;
import com.lms.course.dto.CreateCourseRequest;
import com.lms.course.dto.UpdateCourseRequest;
import com.lms.course.event.CoursePublished;
import com.lms.course.event.LessonRemoved;
import com.lms.course.model.Course;
import com.lms.course.model.CourseLevel;
import com.lms.course.model.CourseStatus;
import com.lms.course.repository.CourseRepository;
import com.lms.course.repository.CourseSpecifications;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CourseService {

    public static final String CACHE = CourseCatalogReader.CACHE;

    private final CourseRepository courses;
    private final CourseCatalogReader catalog;
    private final CourseAccess courseAccess;
    private final ApplicationEventPublisher events;

    public CourseService(CourseRepository courses, CourseCatalogReader catalog,
                         CourseAccess courseAccess, ApplicationEventPublisher events) {
        this.courses = courses;
        this.catalog = catalog;
        this.courseAccess = courseAccess;
        this.events = events;
    }

    @Transactional
    public CourseResponse create(CreateCourseRequest request, AuthenticatedUser caller) {
        Course course = Course.draft(caller.id(), request.title(), request.summary(),
                Money.of(request.price(), request.currency()), request.level());

        if (courses.existsBySlug(course.getSlug())) {
            throw new ConflictException("Another course already uses the link /" + course.getSlug());
        }
        return CourseResponse.withContent(courses.save(course));
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#courseId")
    public CourseResponse update(UUID courseId, UpdateCourseRequest request, AuthenticatedUser caller) {
        Course course = requireOwned(courseId, caller);
        course.rename(request.title());
        course.describe(request.summary());
        course.reprice(Money.of(request.price(), request.currency()));
        return CourseResponse.withContent(course);
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#courseId")
    public CourseResponse addLesson(UUID courseId, AddLessonRequest request, AuthenticatedUser caller) {
        Course course = requireOwned(courseId, caller);
        course.addLesson(request.title(), request.durationMinutes(), request.contentUrl());
        return CourseResponse.withContent(course);
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#courseId")
    public CourseResponse removeLesson(UUID courseId, UUID lessonId, AuthenticatedUser caller) {
        Course course = requireOwned(courseId, caller);
        course.removeLesson(lessonId);

        // Learners may already have been credited for this lesson. Announcing it lets enrolment
        // take that credit back; without it they would be counted towards a course that no
        // longer contains the work.
        events.publishEvent(new LessonRemoved(courseId, lessonId, course.getLessons().size()));
        return CourseResponse.withContent(course);
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#courseId")
    public CourseResponse publish(UUID courseId, AuthenticatedUser caller) {
        Course course = requireOwned(courseId, caller);
        course.publish();

        events.publishEvent(new CoursePublished(course.getId(), course.getInstructorId(), course.getTitle(),
                course.getPrice().amount(), course.getPrice().currencyCode()));
        return CourseResponse.withContent(course);
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE, key = "#courseId")
    public CourseResponse archive(UUID courseId, AuthenticatedUser caller) {
        Course course = requireOwned(courseId, caller);
        course.archive();
        return CourseResponse.withContent(course);
    }

    /**
     * Course detail, shown at the level the caller is entitled to.
     *
     * <p>The syllabus is cached and served to everybody. The version carrying lesson content is
     * read fresh, because what it contains depends on who is asking.
     */
    @Transactional(readOnly = true)
    public CourseResponse byId(UUID courseId, AuthenticatedUser caller) {
        CourseResponse syllabus = catalog.syllabusById(courseId);
        return maySeeContent(syllabus, courseId, caller)
                ? CourseResponse.withContent(require(courseId))
                : syllabus;
    }

    @Transactional(readOnly = true)
    public CourseResponse bySlug(String slug, AuthenticatedUser caller) {
        CourseResponse syllabus = catalog.syllabusBySlug(slug);
        return maySeeContent(syllabus, syllabus.id(), caller)
                ? CourseResponse.withContent(require(syllabus.id()))
                : syllabus;
    }

    /** Content goes to the instructor who owns the course, to admins, and to paid-up learners. */
    private boolean maySeeContent(CourseResponse course, UUID courseId, AuthenticatedUser caller) {
        if (caller == null) {
            return false;
        }
        return caller.isAdmin()
                || course.instructorId().equals(caller.id())
                || courseAccess.maySeeContentOf(courseId, caller.id());
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseSummaryResponse> search(String text, CourseLevel level, CourseStatus status,
                                                      UUID instructorId, Boolean freeOnly, PageQuery query) {
        var specification = CourseSpecifications.matching(text, level, status, instructorId, freeOnly);
        var page = courses.findAll(specification, query.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(page, CourseSummaryResponse::from);
    }

    /**
     * What other features are allowed to know about a course. Returning a record rather than the
     * entity keeps the catalog's storage model out of their reach.
     */
    @Transactional(readOnly = true)
    public CourseFacts factsFor(UUID courseId) {
        Course course = require(courseId);
        return new CourseFacts(course.getId(), course.getInstructorId(), course.isOpenForEnrolment(),
                course.isFree(), course.getPrice().amount(), course.getPrice().currencyCode(),
                course.lessonIds());
    }

    /** True when this course belongs to the caller, or the caller is an administrator. */
    @Transactional(readOnly = true)
    public boolean isOwnedBy(UUID courseId, AuthenticatedUser caller) {
        return caller.isAdmin() || require(courseId).getInstructorId().equals(caller.id());
    }

    @Transactional(readOnly = true)
    public Course require(UUID courseId) {
        return courses.findWithLessonsById(courseId)
                .orElseThrow(() -> NotFoundException.of("Course", courseId));
    }

    private Course requireOwned(UUID courseId, AuthenticatedUser caller) {
        Course course = require(courseId);
        course.requireOwnedBy(caller.id(), caller.isAdmin());
        return course;
    }
}

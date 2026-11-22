package com.lms.course.controller;

import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.course.dto.CourseResponse;
import com.lms.course.dto.CourseSummaryResponse;
import com.lms.course.dto.CreateCourseRequest;
import com.lms.course.dto.UpdateCourseRequest;
import com.lms.course.model.CourseLevel;
import com.lms.course.model.CourseStatus;
import com.lms.course.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Courses")
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @Operation(summary = "Browse the catalog")
    @GetMapping
    public PageResponse<CourseSummaryResponse> search(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) CourseLevel level,
                                                      @RequestParam(required = false) Boolean free,
                                                      @RequestParam(required = false) UUID instructorId,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        // Visitors see published courses. An instructor listing their own catalog, or an admin,
        // also sees drafts — that is how the authoring screen shows work in progress.
        boolean maySeeDrafts = caller != null && (caller.isAdmin() || caller.is(instructorId));
        CourseStatus status = maySeeDrafts ? null : CourseStatus.PUBLISHED;
        return courseService.search(q, level, status, instructorId, free, PageQuery.of(page, size));
    }

    @Operation(summary = "Read one course. Lesson content is included only for those entitled to it")
    @GetMapping("/{courseId}")
    public CourseResponse byId(@PathVariable UUID courseId,
                               @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.byId(courseId, caller);
    }

    @Operation(summary = "Read one course by its public link")
    @GetMapping("/by-slug/{slug}")
    public CourseResponse bySlug(@PathVariable String slug,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.bySlug(slug, caller);
    }

    @Operation(summary = "Create a draft course")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public CourseResponse create(@Valid @RequestBody CreateCourseRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.create(request, caller);
    }

    @Operation(summary = "Change a course's title, summary or price")
    @PutMapping("/{courseId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public CourseResponse update(@PathVariable UUID courseId, @Valid @RequestBody UpdateCourseRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.update(courseId, request, caller);
    }

    @Operation(summary = "Publish a draft so learners can enrol")
    @PostMapping("/{courseId}/publish")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public CourseResponse publish(@PathVariable UUID courseId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.publish(courseId, caller);
    }

    @Operation(summary = "Withdraw a course from the catalog")
    @PostMapping("/{courseId}/archive")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public CourseResponse archive(@PathVariable UUID courseId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.archive(courseId, caller);
    }
}

package com.lms.enrollment.controller;

import com.lms.common.error.ForbiddenException;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.enrollment.dto.EnrollRequest;
import com.lms.enrollment.dto.EnrollmentResponse;
import com.lms.course.service.CourseService;
import com.lms.enrollment.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Enrollments")
@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final CourseService courses;

    public EnrollmentController(EnrollmentService enrollmentService, CourseService courses) {
        this.enrollmentService = enrollmentService;
        this.courses = courses;
    }

    @Operation(summary = "Enrol the signed-in learner in a course")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse enrol(@Valid @RequestBody EnrollRequest request,
                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        return enrollmentService.enrol(caller.id(), request.courseId());
    }

    @Operation(summary = "The signed-in learner's enrolments")
    @GetMapping("/me")
    public PageResponse<EnrollmentResponse> mine(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return enrollmentService.forStudent(caller.id(), PageQuery.of(page, size));
    }

    @Operation(summary = "Read one enrolment")
    @GetMapping("/{enrollmentId}")
    public EnrollmentResponse byId(@PathVariable UUID enrollmentId,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return enrollmentService.byId(enrollmentId, caller);
    }

    @Operation(summary = "Record that a lesson has been finished")
    @PostMapping("/{enrollmentId}/lessons/{lessonId}/complete")
    public EnrollmentResponse completeLesson(@PathVariable UUID enrollmentId, @PathVariable UUID lessonId,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        return enrollmentService.completeLesson(enrollmentId, lessonId, caller);
    }

    @Operation(summary = "Withdraw from a course")
    @PostMapping("/{enrollmentId}/cancel")
    public EnrollmentResponse cancel(@PathVariable UUID enrollmentId,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        return enrollmentService.cancel(enrollmentId, caller);
    }

    @Operation(summary = "Who is enrolled in a course; the owning instructor's roster")
    @GetMapping("/by-course/{courseId}")
    @PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
    public PageResponse<EnrollmentResponse> byCourse(@PathVariable UUID courseId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        // Holding the instructor role is not the same as owning this course. Without this check
        // any instructor could read the roster and progress of every other instructor's learners.
        if (!courses.isOwnedBy(courseId, caller)) {
            throw new ForbiddenException("Course " + courseId + " belongs to another instructor");
        }
        return enrollmentService.forCourse(courseId, PageQuery.of(page, size));
    }
}

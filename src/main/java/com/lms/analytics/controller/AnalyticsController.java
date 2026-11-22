package com.lms.analytics.controller;

import com.lms.analytics.dto.CourseMetricsResponse;
import com.lms.analytics.dto.PlatformOverviewResponse;
import com.lms.analytics.service.AnalyticsService;
import com.lms.common.error.ForbiddenException;
import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.common.security.AuthenticatedUser;
import com.lms.course.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Analytics")
@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final CourseService courses;

    public AnalyticsController(AnalyticsService analyticsService, CourseService courses) {
        this.analyticsService = analyticsService;
        this.courses = courses;
    }

    @Operation(summary = "Enrolments, completions and revenue for one of your own courses")
    @GetMapping("/courses/{courseId}")
    public CourseMetricsResponse forCourse(@PathVariable UUID courseId,
                                           @AuthenticationPrincipal AuthenticatedUser caller) {
        // An instructor's own figures only. Course performance and revenue are commercially
        // sensitive between instructors on the same platform.
        if (!courses.isOwnedBy(courseId, caller)) {
            throw new ForbiddenException("Course " + courseId + " belongs to another instructor");
        }
        return analyticsService.forCourse(courseId);
    }

    @Operation(summary = "Figures for every course on the platform")
    @GetMapping("/courses")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<CourseMetricsResponse> courses(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return analyticsService.allCourses(PageQuery.of(page, size));
    }

    @Operation(summary = "Platform-wide totals")
    @GetMapping("/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformOverviewResponse overview() {
        return analyticsService.overview();
    }
}

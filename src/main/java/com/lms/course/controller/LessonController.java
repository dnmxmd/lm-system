package com.lms.course.controller;

import com.lms.common.security.AuthenticatedUser;
import com.lms.course.dto.AddLessonRequest;
import com.lms.course.dto.CourseResponse;
import com.lms.course.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Lessons")
@RestController
@RequestMapping("/api/v1/courses/{courseId}/lessons")
@PreAuthorize("hasAnyRole('INSTRUCTOR', 'ADMIN')")
public class LessonController {

    private final CourseService courseService;

    public LessonController(CourseService courseService) {
        this.courseService = courseService;
    }

    @Operation(summary = "Append a lesson to the course")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse add(@PathVariable UUID courseId, @Valid @RequestBody AddLessonRequest request,
                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.addLesson(courseId, request, caller);
    }

    @Operation(summary = "Remove a lesson; the remaining lessons close the gap")
    @DeleteMapping("/{lessonId}")
    public CourseResponse remove(@PathVariable UUID courseId, @PathVariable UUID lessonId,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return courseService.removeLesson(courseId, lessonId, caller);
    }
}

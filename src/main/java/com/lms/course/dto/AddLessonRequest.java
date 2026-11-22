package com.lms.course.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AddLessonRequest(
        @NotBlank @Size(max = 200) String title,
        @Positive @Max(1440) int durationMinutes,
        @Size(max = 2048) String contentUrl) {
}

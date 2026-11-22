package com.lms.course.dto;

import com.lms.course.model.CourseLevel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCourseRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String summary,
        @NotNull @PositiveOrZero @DecimalMax("999999.99") BigDecimal price,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a three-letter ISO code") String currency,
        CourseLevel level) {
}

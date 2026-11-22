package com.lms.analytics.model;

/**
 * The platform roll-up, built straight out of the query.
 *
 * <p>A constructor expression rather than {@code Object[]}: an aggregate query hands back a wrapped
 * row, and reading it by index is exactly the kind of code that compiles, reviews fine and throws
 * in production.
 */
public record PlatformTotals(Long coursesWithActivity, Long enrollments, Long completions,
                             Long lessonsCompleted) {
}

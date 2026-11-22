package com.lms.analytics.dto;

import com.lms.analytics.model.CurrencyTotal;
import com.lms.analytics.model.PlatformTotals;

import java.util.List;

/**
 * @param revenue gross revenue per currency: what learners were charged on payments that
 *                succeeded. Refunds are recorded against the payment and are not deducted here.
 */
public record PlatformOverviewResponse(long coursesWithActivity, long enrollments, long completions,
                                       long lessonsCompleted, int completionRatePercent,
                                       List<CurrencyTotal> revenue) {

    /** An empty platform sums to nulls, so every field is defaulted rather than trusted. */
    public static PlatformOverviewResponse from(PlatformTotals totals, List<CurrencyTotal> revenue) {
        long enrollments = orZero(totals.enrollments());
        long completions = orZero(totals.completions());
        int rate = enrollments == 0 ? 0 : (int) Math.round(completions * 100.0 / enrollments);
        return new PlatformOverviewResponse(orZero(totals.coursesWithActivity()), enrollments,
                completions, orZero(totals.lessonsCompleted()), rate, List.copyOf(revenue));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}

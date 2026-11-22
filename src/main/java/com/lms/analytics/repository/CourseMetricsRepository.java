package com.lms.analytics.repository;

import com.lms.analytics.model.CourseMetrics;
import com.lms.analytics.model.CurrencyTotal;
import com.lms.analytics.model.PlatformTotals;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Counters are moved by atomic statements so that concurrent learners never collide on them.
 *
 * <p>{@code flushAutomatically} matters: these run inside the transaction that caused them, and the
 * work that caused them — the enrolment row — has to reach the database first. The context is
 * deliberately <em>not</em> cleared, which would detach entities the caller is still holding.
 *
 * @return rows affected; zero means there is no row for that course yet.
 */
public interface CourseMetricsRepository extends JpaRepository<CourseMetrics, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("""
            update CourseMetrics m
               set m.enrollments = m.enrollments + 1, m.lastUpdatedAt = :now
             where m.courseId = :courseId
            """)
    int addEnrollment(@Param("courseId") UUID courseId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update CourseMetrics m
               set m.completions = m.completions + 1, m.lastUpdatedAt = :now
             where m.courseId = :courseId
            """)
    int addCompletion(@Param("courseId") UUID courseId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update CourseMetrics m
               set m.lessonsCompleted = m.lessonsCompleted + 1, m.lastUpdatedAt = :now
             where m.courseId = :courseId
            """)
    int addLessonCompleted(@Param("courseId") UUID courseId, @Param("now") Instant now);

    /**
     * The currency check lives in the {@code where} clause rather than in a prior read, so it holds
     * without a lock: a payment in a second currency simply matches no row.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CourseMetrics m
               set m.revenueAmount = coalesce(m.revenueAmount, 0) + :amount,
                   m.revenueCurrency = :currency,
                   m.lastUpdatedAt = :now
             where m.courseId = :courseId
               and (m.revenueCurrency is null or m.revenueCurrency = :currency)
            """)
    int addRevenue(@Param("courseId") UUID courseId, @Param("amount") BigDecimal amount,
                   @Param("currency") String currency, @Param("now") Instant now);

    @Query("""
            select new com.lms.analytics.model.PlatformTotals(
                count(m),
                coalesce(sum(m.enrollments), 0L),
                coalesce(sum(m.completions), 0L),
                coalesce(sum(m.lessonsCompleted), 0L))
            from CourseMetrics m
            """)
    PlatformTotals totals();

    /**
     * Revenue per currency, never summed across them.
     *
     * <p>Adding a USD course to a EUR one and labelling the result with whichever currency sorts
     * first produces a number that is wrong in every currency.
     */
    @Query("""
            select new com.lms.analytics.model.CurrencyTotal(m.revenueCurrency, sum(m.revenueAmount))
            from CourseMetrics m
            where m.revenueCurrency is not null
            group by m.revenueCurrency
            order by m.revenueCurrency
            """)
    List<CurrencyTotal> revenueByCurrency();
}

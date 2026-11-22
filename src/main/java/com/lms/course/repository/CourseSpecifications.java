package com.lms.course.repository;

import com.lms.course.model.Course;
import com.lms.course.model.CourseLevel;
import com.lms.course.model.CourseStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds the catalog query. Every filter is optional; null means "do not filter on this". */
public final class CourseSpecifications {

    private CourseSpecifications() {
    }

    public static Specification<Course> matching(String text, CourseLevel level, CourseStatus status,
                                                 UUID instructorId, Boolean freeOnly) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (level != null) {
                predicates.add(builder.equal(root.get("level"), level));
            }
            if (instructorId != null) {
                predicates.add(builder.equal(root.get("instructorId"), instructorId));
            }
            if (Boolean.TRUE.equals(freeOnly)) {
                predicates.add(builder.equal(root.get("priceAmount"), BigDecimal.ZERO));
            }
            if (text != null && !text.isBlank()) {
                String pattern = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(root.get("summary")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}

package com.lms.course.model;

public enum CourseStatus {
    /** Being written. Invisible in the catalog and impossible to enrol in. */
    DRAFT,
    /** Listed in the catalog and open for enrolment. */
    PUBLISHED,
    /** Withdrawn. Existing learners keep access; nobody new can enrol. */
    ARCHIVED
}

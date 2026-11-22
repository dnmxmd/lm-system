-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_role_created_at ON users (role, created_at DESC);

-- ---------------------------------------------------------------------------
-- Catalog
-- ---------------------------------------------------------------------------
CREATE TABLE courses (
    id             UUID          PRIMARY KEY,
    instructor_id  UUID          NOT NULL REFERENCES users (id),
    title          VARCHAR(200)  NOT NULL,
    slug           VARCHAR(120)  NOT NULL,
    summary        VARCHAR(2000),
    price_amount   NUMERIC(12,2) NOT NULL,
    price_currency VARCHAR(3)    NOT NULL,
    level          VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP(6) WITH TIME ZONE,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_courses_slug UNIQUE (slug),
    CONSTRAINT ck_courses_price_non_negative CHECK (price_amount >= 0)
);

CREATE INDEX idx_courses_status_created_at ON courses (status, created_at DESC);
CREATE INDEX idx_courses_instructor ON courses (instructor_id);

CREATE TABLE lessons (
    id               UUID         PRIMARY KEY,
    course_id        UUID         NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    title            VARCHAR(200) NOT NULL,
    position         INTEGER      NOT NULL,
    duration_minutes INTEGER      NOT NULL,
    content_url      VARCHAR(2048),
    CONSTRAINT ck_lessons_duration_positive CHECK (duration_minutes > 0),
    CONSTRAINT ck_lessons_position_positive CHECK (position > 0)
);

-- Not unique on (course_id, position): removing a lesson renumbers the rest, and Hibernate
-- issues those updates in an order that would trip a unique index mid-flight.
CREATE INDEX idx_lessons_course_position ON lessons (course_id, position);

-- ---------------------------------------------------------------------------
-- Enrolment and progress
-- ---------------------------------------------------------------------------
CREATE TABLE enrollments (
    id               UUID        PRIMARY KEY,
    student_id       UUID        NOT NULL REFERENCES users (id),
    course_id        UUID        NOT NULL REFERENCES courses (id),
    total_lessons    INTEGER     NOT NULL,
    status           VARCHAR(20) NOT NULL,
    enrolled_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    activated_at     TIMESTAMP(6) WITH TIME ZONE,
    completed_at     TIMESTAMP(6) WITH TIME ZONE,
    last_activity_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    -- One enrolment per learner per course. The service checks first; this makes it true.
    CONSTRAINT uq_enrollments_student_course UNIQUE (student_id, course_id),
    CONSTRAINT ck_enrollments_total_lessons CHECK (total_lessons >= 1)
);

CREATE INDEX idx_enrollments_student ON enrollments (student_id, enrolled_at DESC);
CREATE INDEX idx_enrollments_course ON enrollments (course_id, enrolled_at DESC);

CREATE TABLE enrollment_completed_lessons (
    enrollment_id UUID NOT NULL REFERENCES enrollments (id) ON DELETE CASCADE,
    lesson_id     UUID NOT NULL,
    PRIMARY KEY (enrollment_id, lesson_id)
);

-- ---------------------------------------------------------------------------
-- Payments
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                 UUID          PRIMARY KEY,
    enrollment_id      UUID          NOT NULL REFERENCES enrollments (id) ON DELETE CASCADE,
    student_id         UUID          NOT NULL REFERENCES users (id),
    course_id          UUID          NOT NULL REFERENCES courses (id),
    amount             NUMERIC(12,2) NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    provider_reference VARCHAR(100),
    failure_reason     VARCHAR(500),
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    settled_at         TIMESTAMP(6) WITH TIME ZONE,
    version            BIGINT        NOT NULL DEFAULT 0,
    -- One charge per enrolment, so a retried enrolment cannot bill twice.
    CONSTRAINT uq_payments_enrollment UNIQUE (enrollment_id),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payments_student ON payments (student_id, created_at DESC);
CREATE INDEX idx_payments_status ON payments (status);

-- ---------------------------------------------------------------------------
-- Reporting read model
-- ---------------------------------------------------------------------------
CREATE TABLE course_metrics (
    course_id         UUID   PRIMARY KEY REFERENCES courses (id) ON DELETE CASCADE,
    enrollments       BIGINT NOT NULL DEFAULT 0,
    completions       BIGINT NOT NULL DEFAULT 0,
    lessons_completed BIGINT NOT NULL DEFAULT 0,
    revenue_amount    NUMERIC(14,2),
    revenue_currency  VARCHAR(3),
    last_updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_course_metrics_non_negative
        CHECK (enrollments >= 0 AND completions >= 0 AND lessons_completed >= 0)
);

CREATE INDEX idx_course_metrics_enrollments ON course_metrics (enrollments DESC);

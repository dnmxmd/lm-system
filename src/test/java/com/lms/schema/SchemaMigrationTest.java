package com.lms.schema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit tests build their schema from the JPA model, which means a migration could drift away
 * from the entities unnoticed. This one runs the migration as written and then makes Hibernate
 * validate the mappings against it, so the two cannot disagree without the build going red.
 */
@SpringBootTest
@ActiveProfiles("schema")
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void the_migration_creates_every_table_the_application_maps() {
        List<String> tables = jdbc.queryForList(
                "select lower(table_name) from information_schema.tables where table_type = 'BASE TABLE'",
                String.class);

        assertThat(tables).contains("users", "courses", "lessons", "enrollments",
                "enrollment_completed_lessons", "payments", "course_metrics");
    }

    @Test
    void flyway_records_the_migration_as_applied() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);

        assertThat(applied).isNotNull().isPositive();
    }
}

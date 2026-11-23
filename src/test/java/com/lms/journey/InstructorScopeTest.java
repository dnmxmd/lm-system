package com.lms.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.lms.common.security.JwtService;
import com.lms.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Instructors share this platform with each other. Holding the instructor role must not be the
 * same thing as owning a particular course.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class InstructorScopeTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;

    @Test
    void one_instructor_cannot_read_another_instructors_roster_or_figures() {
        String owner = tokenFor("owner-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String rival = tokenFor("rival-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String courseId = publishCourse(owner);

        assertThat(status(HttpMethod.GET, "/api/v1/enrollments/by-course/" + courseId, rival))
                .as("a competitor must not see who is enrolled")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId, rival))
                .as("nor how the course is performing")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(status(HttpMethod.GET, "/api/v1/enrollments/by-course/" + courseId, owner))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId, owner))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void platform_wide_figures_are_for_admins_only() {
        String instructor = tokenFor("curious-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();

        assertThat(status(HttpMethod.GET, "/api/v1/analytics/courses", instructor))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/analytics/overview", instructor))
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(status(HttpMethod.GET, "/api/v1/analytics/courses", admin)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/analytics/overview", admin)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void an_admin_can_still_look_at_any_course() {
        String owner = tokenFor("owner2-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();
        String courseId = publishCourse(owner);

        assertThat(status(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId, admin))
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ helpers

    private String publishCourse(String instructor) {
        JsonNode created = exchange(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Owned course %s","summary":"Mine","price":0.00,"currency":"USD"}
                """.formatted(UUID.randomUUID()), instructor).getBody();
        String courseId = created.get("id").asText();
        exchange(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                "{\"title\":\"Only lesson\",\"durationMinutes\":10}", instructor);
        exchange(HttpMethod.POST, "/api/v1/courses/" + courseId + "/publish", null, instructor);
        return courseId;
    }

    private String tokenFor(String email, String role) {
        exchange(HttpMethod.POST, "/api/v1/auth/register", """
                {"email":"%s","password":"hunter2024","fullName":"A Person","role":"%s"}
                """.formatted(email, role), null);
        return exchange(HttpMethod.POST, "/api/v1/auth/login", """
                {"email":"%s","password":"hunter2024"}
                """.formatted(email), null).getBody().get("accessToken").asText();
    }

    private HttpStatus status(HttpMethod method, String path, String token) {
        return (HttpStatus) exchange(method, path, null, token).getStatusCode();
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}

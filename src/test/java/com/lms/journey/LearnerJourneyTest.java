package com.lms.journey;

import com.fasterxml.jackson.databind.JsonNode;
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
 * The whole paid-course journey over real HTTP, against the schema the migration produces.
 *
 * <p>The unit tests check each piece in isolation; this is the one that would catch the seams —
 * security rules, JSON shapes, the event that raises a charge, and the transaction that both
 * settles it and opens the course.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class LearnerJourneyTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void a_learner_signs_up_pays_and_works_through_a_course() {
        String instructorEmail = "teacher-" + UUID.randomUUID() + "@example.com";
        String studentEmail = "learner-" + UUID.randomUUID() + "@example.com";

        // --- accounts -------------------------------------------------------
        assertThat(register(instructorEmail, "Tess Teacher", "INSTRUCTOR").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(register(studentEmail, "Stan Student", "STUDENT").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String instructorToken = login(instructorEmail);
        String studentToken = login(studentEmail);

        // --- authoring ------------------------------------------------------
        JsonNode course = post("/api/v1/courses", """
                {"title":"Clean Architecture in Java","summary":"Layers that hold up",
                 "price":49.99,"currency":"USD","level":"INTERMEDIATE"}
                """, instructorToken, HttpStatus.CREATED);
        String courseId = course.get("id").asText();
        assertThat(course.get("status").asText()).isEqualTo("DRAFT");
        assertThat(course.get("slug").asText()).isEqualTo("clean-architecture-in-java");

        post("/api/v1/courses/" + courseId + "/lessons",
                """
                {"title":"Why layers","durationMinutes":12}
                """, instructorToken, HttpStatus.CREATED);
        JsonNode withLessons = post("/api/v1/courses/" + courseId + "/lessons",
                """
                {"title":"Ports and adapters","durationMinutes":18}
                """, instructorToken, HttpStatus.CREATED);
        assertThat(withLessons.get("lessons")).hasSize(2);
        assertThat(withLessons.get("totalDurationMinutes").asInt()).isEqualTo(30);

        String firstLesson = withLessons.get("lessons").get(0).get("id").asText();
        String secondLesson = withLessons.get("lessons").get(1).get("id").asText();

        JsonNode published = post("/api/v1/courses/" + courseId + "/publish", null,
                instructorToken, HttpStatus.OK);
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");

        // The catalog is readable without an account.
        JsonNode catalog = get("/api/v1/courses?q=Clean", null, HttpStatus.OK);
        assertThat(catalog.get("totalElements").asLong()).isPositive();

        // --- enrolment ------------------------------------------------------
        JsonNode enrollment = post("/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", studentToken, HttpStatus.CREATED);
        String enrollmentId = enrollment.get("id").asText();
        assertThat(enrollment.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(enrollment.get("totalLessons").asInt()).isEqualTo(2);

        // Enrolling in a paid course raises the charge on its own.
        JsonNode payment = get("/api/v1/payments/by-enrollment/" + enrollmentId, studentToken, HttpStatus.OK);
        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("49.99");

        // A pending enrolment grants no access to the lessons.
        assertThat(rawPost("/api/v1/enrollments/" + enrollmentId + "/lessons/" + firstLesson + "/complete",
                null, studentToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // --- payment --------------------------------------------------------
        JsonNode settled = post("/api/v1/payments/" + payment.get("id").asText() + "/confirm",
                """
                {"providerReference":"ch_test_1234567890"}
                """, studentToken, HttpStatus.OK);
        assertThat(settled.get("status").asText()).isEqualTo("SUCCEEDED");

        assertThat(get("/api/v1/enrollments/" + enrollmentId, studentToken, HttpStatus.OK)
                .get("status").asText()).isEqualTo("ACTIVE");

        // A second confirmation cannot charge twice.
        assertThat(rawPost("/api/v1/payments/" + payment.get("id").asText() + "/confirm",
                "{\"providerReference\":\"ch_duplicate\"}", studentToken).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // --- progress -------------------------------------------------------
        JsonNode halfway = post("/api/v1/enrollments/" + enrollmentId + "/lessons/" + firstLesson
                + "/complete", null, studentToken, HttpStatus.OK);
        assertThat(halfway.get("progressPercent").asInt()).isEqualTo(50);

        JsonNode done = post("/api/v1/enrollments/" + enrollmentId + "/lessons/" + secondLesson
                + "/complete", null, studentToken, HttpStatus.OK);
        assertThat(done.get("progressPercent").asInt()).isEqualTo(100);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");

        // --- reporting ------------------------------------------------------
        JsonNode metrics = get("/api/v1/analytics/courses/" + courseId, instructorToken, HttpStatus.OK);
        assertThat(metrics.get("enrollments").asLong()).isEqualTo(1);
        assertThat(metrics.get("completions").asLong()).isEqualTo(1);
        assertThat(metrics.get("lessonsCompleted").asLong()).isEqualTo(2);
        assertThat(metrics.get("completionRatePercent").asInt()).isEqualTo(100);
        assertThat(metrics.get("revenue").decimalValue()).isEqualByComparingTo("49.99");
    }

    @Test
    void an_anonymous_caller_cannot_read_enrolments() {
        assertThat(http.getForEntity("/api/v1/enrollments/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_learner_cannot_reach_the_platform_wide_report() {
        String email = "nosy-" + UUID.randomUUID() + "@example.com";
        register(email, "Nosy Parker", "STUDENT");

        ResponseEntity<String> response = http.exchange("/api/v1/analytics/overview", HttpMethod.GET,
                new HttpEntity<>(authHeaders(login(email))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void registering_the_same_address_twice_is_refused() {
        String email = "twice-" + UUID.randomUUID() + "@example.com";
        register(email, "First Go", "STUDENT");

        assertThat(register(email, "Second Go", "STUDENT").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void a_malformed_registration_is_reported_field_by_field() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/auth/register",
                json("""
                     {"email":"not-an-address","password":"short","fullName":""}
                     """), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().get("violations")).hasSizeGreaterThanOrEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<JsonNode> register(String email, String fullName, String role) {
        return http.postForEntity("/api/v1/auth/register", json("""
                {"email":"%s","password":"hunter2024","fullName":"%s","role":"%s"}
                """.formatted(email, fullName, role)), JsonNode.class);
    }

    private String login(String email) {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/auth/login", json("""
                {"email":"%s","password":"hunter2024"}
                """.formatted(email)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    private JsonNode get(String path, String token, HttpStatus expected) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(expected);
        return response.getBody();
    }

    private JsonNode post(String path, String body, String token, HttpStatus expected) {
        ResponseEntity<JsonNode> response = rawPost(path, body, token);
        assertThat(response.getStatusCode()).as("POST %s -> %s", path, response.getBody()).isEqualTo(expected);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> rawPost(String path, String body, String token) {
        return http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), JsonNode.class);
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}

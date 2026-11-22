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
 * The catalog is public so people can decide whether to buy. What it must not do is hand over the
 * thing they would be buying.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class LessonContentVisibilityTest {

    private static final String CONTENT = "https://cdn.example.com/private/lesson-one.mp4";

    @Autowired
    private TestRestTemplate http;

    @Test
    void the_public_catalog_lists_lessons_but_not_their_content() {
        String courseId = publishPaidCourseWithContent();

        JsonNode anonymous = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, null, HttpStatus.OK);

        assertThat(anonymous.get("lessons")).as("titles and lengths are how people choose").hasSize(1);
        assertThat(anonymous.get("lessons").get(0).get("title").asText()).isEqualTo("Only lesson");
        assertThat(anonymous.toString())
                .as("the content URL is the product; it must not be in a public response")
                .doesNotContain(CONTENT);
    }

    @Test
    void a_learner_who_has_not_paid_still_cannot_see_the_content() {
        String courseId = publishPaidCourseWithContent();
        String student = tokenFor("window-shopper-" + UUID.randomUUID() + "@example.com", "STUDENT");

        send(HttpMethod.POST, "/api/v1/enrollments", "{\"courseId\":\"" + courseId + "\"}",
                student, HttpStatus.CREATED);

        JsonNode pending = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, student, HttpStatus.OK);

        assertThat(pending.toString())
                .as("the enrolment is still awaiting payment")
                .doesNotContain(CONTENT);
    }

    @Test
    void the_owning_instructor_can_see_the_content_they_uploaded() {
        String instructor = tokenFor("tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String courseId = publishPaidCourseWithContent(instructor);

        JsonNode owner = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, instructor, HttpStatus.OK);

        assertThat(owner.toString()).contains(CONTENT);
    }

    @Test
    void a_learner_who_has_paid_can_see_the_content() {
        String courseId = publishPaidCourseWithContent();
        String student = tokenFor("paid-" + UUID.randomUUID() + "@example.com", "STUDENT");

        String enrollmentId = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED).get("id").asText();
        String paymentId = send(HttpMethod.GET, "/api/v1/payments/by-enrollment/" + enrollmentId,
                null, student, HttpStatus.OK).get("id").asText();
        send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/confirm",
                "{\"providerReference\":\"ch_paid\"}", student, HttpStatus.OK);

        JsonNode enrolled = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, student, HttpStatus.OK);

        assertThat(enrolled.toString()).contains(CONTENT);
    }

    // ------------------------------------------------------------------ helpers

    private String publishPaidCourseWithContent() {
        return publishPaidCourseWithContent(tokenFor("tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR"));
    }

    private String publishPaidCourseWithContent(String instructor) {
        String courseId = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Paid course %s","summary":"Costs money",
                 "price":49.99,"currency":"USD","level":"BEGINNER"}
                """.formatted(UUID.randomUUID()), instructor, HttpStatus.CREATED).get("id").asText();
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                "{\"title\":\"Only lesson\",\"durationMinutes\":10,\"contentUrl\":\"" + CONTENT + "\"}",
                instructor, HttpStatus.CREATED);
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/publish", null, instructor, HttpStatus.OK);
        return courseId;
    }

    private String tokenFor(String email, String role) {
        send(HttpMethod.POST, "/api/v1/auth/register", """
                {"email":"%s","password":"hunter2024","fullName":"A Person","role":"%s"}
                """.formatted(email, role), null, HttpStatus.CREATED);
        return send(HttpMethod.POST, "/api/v1/auth/login", """
                {"email":"%s","password":"hunter2024"}
                """.formatted(email), null, HttpStatus.OK).get("accessToken").asText();
    }

    private JsonNode send(HttpMethod method, String path, String body, String token, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response =
                http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).as("%s %s -> %s", method, path, response.getBody())
                .isEqualTo(expected);
        return response.getBody();
    }
}

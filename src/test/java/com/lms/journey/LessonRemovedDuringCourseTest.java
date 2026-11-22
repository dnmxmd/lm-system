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
 * Instructors edit courses that people are already part way through. The question this asks is
 * whether a learner can be credited for work the course no longer contains.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class LessonRemovedDuringCourseTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void removing_a_finished_lesson_does_not_hand_the_learner_a_free_completion() {
        String instructorToken = tokenFor("tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String studentToken = tokenFor("stan-" + UUID.randomUUID() + "@example.com", "STUDENT");

        // A free course with three lessons.
        JsonNode course = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Three parter %s","summary":"Work in progress",
                 "price":0.00,"currency":"USD","level":"BEGINNER"}
                """.formatted(UUID.randomUUID()), instructorToken, HttpStatus.CREATED);
        String courseId = course.get("id").asText();

        for (String title : new String[]{"One", "Two", "Three"}) {
            course = send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                    "{\"title\":\"%s\",\"durationMinutes\":10}".formatted(title),
                    instructorToken, HttpStatus.CREATED);
        }
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/publish", null,
                instructorToken, HttpStatus.OK);

        String lessonOne = course.get("lessons").get(0).get("id").asText();

        // The learner enrols and finishes only the first lesson.
        JsonNode enrollment = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", studentToken, HttpStatus.CREATED);
        String enrollmentId = enrollment.get("id").asText();

        JsonNode afterFirst = send(HttpMethod.POST,
                "/api/v1/enrollments/" + enrollmentId + "/lessons/" + lessonOne + "/complete",
                null, studentToken, HttpStatus.OK);
        assertThat(afterFirst.get("progressPercent").asInt()).isEqualTo(33);

        // The instructor then drops that very lesson from the course.
        JsonNode trimmed = send(HttpMethod.DELETE,
                "/api/v1/courses/" + courseId + "/lessons/" + lessonOne, null,
                instructorToken, HttpStatus.OK);
        assertThat(trimmed.get("lessons")).hasSize(2);

        String remainingFirst = trimmed.get("lessons").get(0).get("id").asText();

        // The learner finishes one of the two lessons that are actually left.
        JsonNode afterSecond = send(HttpMethod.POST,
                "/api/v1/enrollments/" + enrollmentId + "/lessons/" + remainingFirst + "/complete",
                null, studentToken, HttpStatus.OK);

        assertThat(afterSecond.get("status").asText())
                .as("one of the two remaining lessons is still outstanding")
                .isEqualTo("ACTIVE");
        assertThat(afterSecond.get("completedLessons").asInt())
                .as("credit for a lesson the course no longer has must not be kept")
                .isEqualTo(1);
        assertThat(afterSecond.get("progressPercent").asInt()).isEqualTo(50);
    }

    // ------------------------------------------------------------------ helpers

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

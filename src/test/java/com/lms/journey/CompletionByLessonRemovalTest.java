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
 * An instructor can finish a learner's course for them by deleting what was left.
 *
 * <p>That is a real completion and has to behave like one: counted in reporting, and the learner
 * told. Before this it happened silently — the status changed and nothing else noticed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class CompletionByLessonRemovalTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;

    @Test
    void removing_the_last_unfinished_lesson_completes_counts_and_announces() {
        String instructor = tokenFor("trim-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String student = tokenFor("learner-" + UUID.randomUUID() + "@example.com", "STUDENT");

        JsonNode course = createCourse(instructor, "One", "Two", "Three");
        String courseId = course.get("id").asText();
        String lessonOne = course.get("lessons").get(0).get("id").asText();
        String lessonTwo = course.get("lessons").get(1).get("id").asText();
        String lessonThree = course.get("lessons").get(2).get("id").asText();

        String enrollmentId = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED).get("id").asText();

        complete(enrollmentId, lessonOne, student);
        JsonNode twoOfThree = complete(enrollmentId, lessonTwo, student);
        assertThat(twoOfThree.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(twoOfThree.get("progressPercent").asInt()).isEqualTo(67);

        long completionsBefore = completions(courseId, instructor);
        int noticesBefore = completionNotices(enrollmentId);

        // The instructor drops the one lesson the learner had not done.
        send(HttpMethod.DELETE, "/api/v1/courses/" + courseId + "/lessons/" + lessonThree,
                null, instructor, HttpStatus.OK);

        assertThat(send(HttpMethod.GET, "/api/v1/enrollments/" + enrollmentId, null, student, HttpStatus.OK)
                .get("status").asText())
                .as("everything that remains has been done")
                .isEqualTo("COMPLETED");
        assertThat(completions(courseId, instructor))
                .as("reporting has to see it too")
                .isEqualTo(completionsBefore + 1);
        assertThat(completionNotices(enrollmentId))
                .as("and the learner has to be told")
                .isEqualTo(noticesBefore + 1);
    }

    @Test
    void trimming_a_course_again_does_not_announce_an_already_finished_enrolment_twice() {
        String instructor = tokenFor("trim2-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String student = tokenFor("learner2-" + UUID.randomUUID() + "@example.com", "STUDENT");

        JsonNode course = createCourse(instructor, "One", "Two", "Three");
        String courseId = course.get("id").asText();
        String lessonOne = course.get("lessons").get(0).get("id").asText();
        String lessonTwo = course.get("lessons").get(1).get("id").asText();
        String lessonThree = course.get("lessons").get(2).get("id").asText();

        String enrollmentId = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED).get("id").asText();
        complete(enrollmentId, lessonOne, student);
        complete(enrollmentId, lessonTwo, student);

        send(HttpMethod.DELETE, "/api/v1/courses/" + courseId + "/lessons/" + lessonThree,
                null, instructor, HttpStatus.OK);
        int afterFirstTrim = completionNotices(enrollmentId);
        long completionsAfterFirstTrim = completions(courseId, instructor);

        // Trimming again: the enrolment is already complete and must not be announced twice.
        send(HttpMethod.DELETE, "/api/v1/courses/" + courseId + "/lessons/" + lessonTwo,
                null, instructor, HttpStatus.OK);

        assertThat(completionNotices(enrollmentId)).isEqualTo(afterFirstTrim);
        assertThat(completions(courseId, instructor)).isEqualTo(completionsAfterFirstTrim);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode complete(String enrollmentId, String lessonId, String student) {
        return send(HttpMethod.POST,
                "/api/v1/enrollments/" + enrollmentId + "/lessons/" + lessonId + "/complete",
                null, student, HttpStatus.OK);
    }

    private long completions(String courseId, String instructor) {
        return send(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId, null, instructor,
                HttpStatus.OK).get("completions").asLong();
    }

    private int completionNotices(String enrollmentId) {
        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();
        JsonNode events = send(HttpMethod.GET, "/api/v1/events?size=200", null, admin, HttpStatus.OK);
        int count = 0;
        for (JsonNode event : events.get("content")) {
            if ("EnrollmentCompleted".equals(event.get("type").asText())
                    && event.get("payload").asText().contains(enrollmentId)) {
                count++;
            }
        }
        return count;
    }

    private JsonNode createCourse(String instructor, String... lessons) {
        String courseId = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Trimmable %s","summary":"Free","price":0.00,"currency":"USD"}
                """.formatted(UUID.randomUUID()), instructor, HttpStatus.CREATED).get("id").asText();
        JsonNode latest = null;
        for (String lesson : lessons) {
            latest = send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                    "{\"title\":\"%s\",\"durationMinutes\":10}".formatted(lesson),
                    instructor, HttpStatus.CREATED);
        }
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/publish", null, instructor, HttpStatus.OK);
        return latest;
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

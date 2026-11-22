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
 * Cards get declined. What matters is whether the learner can try again afterwards, or whether a
 * single decline locks them out of the course for good.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class PaymentRetryTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void a_declined_card_does_not_lock_the_learner_out_of_the_course() {
        String courseId = publishPaidCourse();
        String student = tokenFor("retry-" + UUID.randomUUID() + "@example.com", "STUDENT");

        JsonNode enrollment = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED);
        String enrollmentId = enrollment.get("id").asText();

        String paymentId = send(HttpMethod.GET, "/api/v1/payments/by-enrollment/" + enrollmentId,
                null, student, HttpStatus.OK).get("id").asText();

        // The card is declined.
        JsonNode declined = send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/fail",
                "{\"reason\":\"Card declined\"}", student, HttpStatus.OK);
        assertThat(declined.get("status").asText()).isEqualTo("FAILED");

        // The learner tries again with a different card. This must lead somewhere.
        JsonNode retried = send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/retry",
                null, student, HttpStatus.OK);
        assertThat(retried.get("status").asText()).isEqualTo("PENDING");

        JsonNode settled = send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/confirm",
                "{\"providerReference\":\"ch_second_attempt\"}", student, HttpStatus.OK);
        assertThat(settled.get("status").asText()).isEqualTo("SUCCEEDED");

        assertThat(send(HttpMethod.GET, "/api/v1/enrollments/" + enrollmentId, null, student, HttpStatus.OK)
                .get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void withdrawing_from_a_course_does_not_bar_the_learner_from_coming_back() {
        String courseId = publishPaidCourse();
        String student = tokenFor("returner-" + UUID.randomUUID() + "@example.com", "STUDENT");

        String enrollmentId = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED).get("id").asText();
        send(HttpMethod.POST, "/api/v1/enrollments/" + enrollmentId + "/cancel", null,
                student, HttpStatus.OK);

        JsonNode again = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED);

        assertThat(again.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(again.get("completedLessons").asInt()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String publishPaidCourse() {
        String instructor = tokenFor("tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String courseId = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Paid course %s","summary":"Costs money",
                 "price":49.99,"currency":"USD","level":"BEGINNER"}
                """.formatted(UUID.randomUUID()), instructor, HttpStatus.CREATED).get("id").asText();
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                "{\"title\":\"Only lesson\",\"durationMinutes\":10}", instructor, HttpStatus.CREATED);
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

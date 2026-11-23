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
 * Withdrawing and rejoining a paid course.
 *
 * <p>The agreed rule: <b>a successful, unrefunded purchase grants re-entry.</b> Somebody who paid
 * walks straight back in. Somebody who never paid still owes the same charge. Somebody who was
 * refunded has to buy it again, and the refund stays on the record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class ReEnrolmentTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;

    @Test
    void a_learner_who_paid_and_withdrew_walks_back_in_without_paying_again() {
        String instructor = tokenFor("owner-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String courseId = publishPaidCourse(instructor);
        String student = tokenFor("paid-" + UUID.randomUUID() + "@example.com", "STUDENT");

        String enrollmentId = enrol(courseId, student).get("id").asText();
        String paymentId = currentPayment(enrollmentId, student).get("id").asText();
        send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/confirm",
                "{\"providerReference\":\"ch_first\"}", student, HttpStatus.OK);

        JsonNode revenueBefore = send(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId,
                null, instructor, HttpStatus.OK);

        send(HttpMethod.POST, "/api/v1/enrollments/" + enrollmentId + "/cancel", null,
                student, HttpStatus.OK);

        JsonNode rejoined = enrol(courseId, student);

        assertThat(rejoined.get("status").asText())
                .as("they already own this course")
                .isEqualTo("ACTIVE");
        assertThat(currentPayment(enrollmentId, student).get("id").asText())
                .as("no second charge was raised")
                .isEqualTo(paymentId);

        JsonNode revenueAfter = send(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId,
                null, instructor, HttpStatus.OK);
        assertThat(revenueAfter.get("revenue").decimalValue())
                .as("rejoining is not a sale")
                .isEqualByComparingTo(revenueBefore.get("revenue").decimalValue());

        // The first enrolment did owe money, so only the most recent one is of interest here.
        assertThat(latestEnrolmentEvent(enrollmentId))
                .as("no payment-due notice should be queued for somebody who already paid")
                .contains("\"requiresPayment\":false");
    }

    @Test
    void a_learner_who_never_paid_still_owes_the_same_charge() {
        String courseId = publishPaidCourse(tokenFor("owner2-" + UUID.randomUUID() + "@example.com",
                "INSTRUCTOR"));
        String student = tokenFor("unpaid-" + UUID.randomUUID() + "@example.com", "STUDENT");

        String enrollmentId = enrol(courseId, student).get("id").asText();
        String paymentId = currentPayment(enrollmentId, student).get("id").asText();
        send(HttpMethod.POST, "/api/v1/enrollments/" + enrollmentId + "/cancel", null,
                student, HttpStatus.OK);

        JsonNode rejoined = enrol(courseId, student);

        assertThat(rejoined.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        JsonNode payment = currentPayment(enrollmentId, student);
        assertThat(payment.get("id").asText()).as("the same charge, not a second one").isEqualTo(paymentId);
        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void a_declined_card_is_put_back_in_play_when_the_learner_rejoins() {
        String courseId = publishPaidCourse(tokenFor("owner3-" + UUID.randomUUID() + "@example.com",
                "INSTRUCTOR"));
        String student = tokenFor("declined-" + UUID.randomUUID() + "@example.com", "STUDENT");

        String enrollmentId = enrol(courseId, student).get("id").asText();
        String paymentId = currentPayment(enrollmentId, student).get("id").asText();
        send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/fail",
                "{\"reason\":\"Card declined\"}", student, HttpStatus.OK);
        send(HttpMethod.POST, "/api/v1/enrollments/" + enrollmentId + "/cancel", null,
                student, HttpStatus.OK);

        JsonNode rejoined = enrol(courseId, student);

        assertThat(rejoined.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        JsonNode payment = currentPayment(enrollmentId, student);
        assertThat(payment.get("id").asText()).isEqualTo(paymentId);
        assertThat(payment.get("status").asText())
                .as("the learner must be able to try a different card")
                .isEqualTo("PENDING");
    }

    @Test
    void a_refunded_learner_buys_the_course_again_and_the_refund_is_kept() {
        String instructor = tokenFor("owner4-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String courseId = publishPaidCourse(instructor);
        String student = tokenFor("refunded-" + UUID.randomUUID() + "@example.com", "STUDENT");
        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();

        String enrollmentId = enrol(courseId, student).get("id").asText();
        String firstPayment = currentPayment(enrollmentId, student).get("id").asText();
        send(HttpMethod.POST, "/api/v1/payments/" + firstPayment + "/confirm",
                "{\"providerReference\":\"ch_first\"}", student, HttpStatus.OK);
        send(HttpMethod.POST, "/api/v1/payments/" + firstPayment + "/refund", null, admin, HttpStatus.OK);
        send(HttpMethod.POST, "/api/v1/enrollments/" + enrollmentId + "/cancel", null,
                student, HttpStatus.OK);

        JsonNode rejoined = enrol(courseId, student);

        assertThat(rejoined.get("status").asText())
                .as("the money went back, so access has to be bought again")
                .isEqualTo("PENDING_PAYMENT");

        JsonNode secondPayment = currentPayment(enrollmentId, student);
        assertThat(secondPayment.get("id").asText()).isNotEqualTo(firstPayment);
        assertThat(secondPayment.get("status").asText()).isEqualTo("PENDING");

        assertThat(send(HttpMethod.GET, "/api/v1/payments/" + firstPayment, null, student, HttpStatus.OK)
                .get("status").asText())
                .as("the refund stays on the record")
                .isEqualTo("REFUNDED");

        // And paying the new charge lets them back in.
        send(HttpMethod.POST, "/api/v1/payments/" + secondPayment.get("id").asText() + "/confirm",
                "{\"providerReference\":\"ch_second\"}", student, HttpStatus.OK);
        assertThat(send(HttpMethod.GET, "/api/v1/enrollments/" + enrollmentId, null, student, HttpStatus.OK)
                .get("status").asText()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode enrol(String courseId, String student) {
        return send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED);
    }

    private JsonNode currentPayment(String enrollmentId, String student) {
        return send(HttpMethod.GET, "/api/v1/payments/by-enrollment/" + enrollmentId,
                null, student, HttpStatus.OK);
    }

    /** The newest EnrollmentCreated event for this enrolment; the listing is newest first. */
    private String latestEnrolmentEvent(String enrollmentId) {
        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();
        JsonNode events = send(HttpMethod.GET, "/api/v1/events?size=200", null, admin, HttpStatus.OK);
        for (JsonNode event : events.get("content")) {
            if ("EnrollmentCreated".equals(event.get("type").asText())
                    && event.get("payload").asText().contains(enrollmentId)) {
                return event.get("payload").asText();
            }
        }
        throw new AssertionError("no EnrollmentCreated event for " + enrollmentId);
    }

    private String publishPaidCourse(String instructor) {
        String courseId = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Paid %s","summary":"Costs money","price":49.99,"currency":"USD"}
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

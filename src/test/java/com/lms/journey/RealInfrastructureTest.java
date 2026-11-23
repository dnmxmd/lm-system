package com.lms.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.lms.common.security.JwtService;
import com.lms.common.security.Role;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole journey against real PostgreSQL and real Redis.
 *
 * <p>Everything else runs on H2 with caching switched off, which is fast but hides two whole
 * classes of problem: SQL that only PostgreSQL rejects, and a cache that cannot return what it
 * stored. Both have bitten this project already.
 *
 * <p>Skipped — not failed — when the backing services are not running, so {@code mvn test} still
 * works on a laptop with nothing started. Bring them up with:
 * {@code docker compose up -d postgres redis}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("realinfra")
class RealInfrastructureTest {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;

    @BeforeAll
    static void requireBackingServices() {
        assumeTrue(reachable("localhost", 5432), "PostgreSQL is not running on localhost:5432");
        assumeTrue(reachable("localhost", 6379), "Redis is not running on localhost:6379");
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException unreachable) {
            return false;
        }
    }

    @Test
    void the_course_cache_survives_a_second_read_and_notices_an_edit() {
        String instructor = tokenFor("cache-tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String courseId = publishCourse(instructor, "Cacheable course " + UUID.randomUUID());

        // First read populates the cache; the second must come back as a course, not a map.
        JsonNode first = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, null, HttpStatus.OK);
        JsonNode second = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, null, HttpStatus.OK);

        assertThat(second.get("title").asText()).isEqualTo(first.get("title").asText());
        assertThat(second.get("lessonCount").asInt()).isEqualTo(1);

        // An edit must evict it, or the catalog would serve the old title for ten minutes.
        send(HttpMethod.PUT, "/api/v1/courses/" + courseId, """
                {"title":"Renamed after caching","summary":"Changed","price":0.00,"currency":"USD"}
                """, instructor, HttpStatus.OK);

        JsonNode third = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, null, HttpStatus.OK);
        assertThat(third.get("title").asText()).isEqualTo("Renamed after caching");
    }

    @Test
    void the_cached_public_view_never_carries_lesson_content() {
        String instructor = tokenFor("leak-tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String content = "https://cdn.example.com/secret/" + UUID.randomUUID();
        String courseId = publishPaidCourse(instructor, content);

        // Read twice so the second answer definitely comes from Redis.
        send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, null, HttpStatus.OK);
        JsonNode cached = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, null, HttpStatus.OK);

        assertThat(cached.toString()).doesNotContain(content);
    }

    @Test
    void an_event_is_recorded_and_delivered_by_the_relay() throws Exception {
        String email = "outbox-" + UUID.randomUUID() + "@example.com";
        send(HttpMethod.POST, "/api/v1/auth/register", """
                {"email":"%s","password":"hunter2024","fullName":"Outbox Person","role":"STUDENT"}
                """.formatted(email), null, HttpStatus.CREATED);

        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();

        // Auto-delivery is on outside the unit-test profiles, so the relay should pick this up on
        // its own. Polled rather than asserted once, because delivery is asynchronous by design.
        String status = null;
        for (int attempt = 0; attempt < 40 && !"PUBLISHED".equals(status); attempt++) {
            JsonNode events = send(HttpMethod.GET, "/api/v1/events?size=100", null, admin, HttpStatus.OK);
            for (JsonNode event : events.get("content")) {
                if (event.get("payload").asText().contains(email)) {
                    status = event.get("status").asText();
                }
            }
            if (!"PUBLISHED".equals(status)) {
                Thread.sleep(250);
            }
        }

        assertThat(status)
                .as("the relay should have delivered the registration event")
                .isEqualTo("PUBLISHED");
    }

    @Test
    void the_full_paid_journey_works_on_postgres() {
        String instructor = tokenFor("pg-tess-" + UUID.randomUUID() + "@example.com", "INSTRUCTOR");
        String student = tokenFor("pg-stan-" + UUID.randomUUID() + "@example.com", "STUDENT");
        String content = "https://cdn.example.com/pg/" + UUID.randomUUID();
        String courseId = publishPaidCourse(instructor, content);

        String enrollmentId = send(HttpMethod.POST, "/api/v1/enrollments",
                "{\"courseId\":\"" + courseId + "\"}", student, HttpStatus.CREATED).get("id").asText();

        String paymentId = send(HttpMethod.GET, "/api/v1/payments/by-enrollment/" + enrollmentId,
                null, student, HttpStatus.OK).get("id").asText();

        // Decline, then retry: the learner must not be stuck.
        send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/fail",
                "{\"reason\":\"Card declined\"}", student, HttpStatus.OK);
        send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/retry", null, student, HttpStatus.OK);
        send(HttpMethod.POST, "/api/v1/payments/" + paymentId + "/confirm",
                "{\"providerReference\":\"ch_pg\"}", student, HttpStatus.OK);

        JsonNode enrolled = send(HttpMethod.GET, "/api/v1/courses/" + courseId, null, student, HttpStatus.OK);
        assertThat(enrolled.toString()).as("a paid-up learner gets the content").contains(content);

        String lessonId = enrolled.get("lessons").get(0).get("id").asText();
        JsonNode done = send(HttpMethod.POST,
                "/api/v1/enrollments/" + enrollmentId + "/lessons/" + lessonId + "/complete",
                null, student, HttpStatus.OK);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");

        JsonNode metrics = send(HttpMethod.GET, "/api/v1/analytics/courses/" + courseId,
                null, instructor, HttpStatus.OK);
        assertThat(metrics.get("enrollments").asLong()).isEqualTo(1);
        assertThat(metrics.get("revenue").decimalValue()).isEqualByComparingTo("49.99");
    }

    // ------------------------------------------------------------------ helpers

    private String publishCourse(String instructor, String title) {
        String courseId = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"%s","summary":"Free","price":0.00,"currency":"USD","level":"BEGINNER"}
                """.formatted(title), instructor, HttpStatus.CREATED).get("id").asText();
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                "{\"title\":\"Only lesson\",\"durationMinutes\":10}", instructor, HttpStatus.CREATED);
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/publish", null, instructor, HttpStatus.OK);
        return courseId;
    }

    private String publishPaidCourse(String instructor, String contentUrl) {
        String courseId = send(HttpMethod.POST, "/api/v1/courses", """
                {"title":"Paid %s","summary":"Costs money","price":49.99,"currency":"USD","level":"BEGINNER"}
                """.formatted(UUID.randomUUID()), instructor, HttpStatus.CREATED).get("id").asText();
        send(HttpMethod.POST, "/api/v1/courses/" + courseId + "/lessons",
                "{\"title\":\"Only lesson\",\"durationMinutes\":10,\"contentUrl\":\"" + contentUrl + "\"}",
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

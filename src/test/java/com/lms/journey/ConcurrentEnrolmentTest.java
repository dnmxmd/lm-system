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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several learners enrolling in the same course at the same moment.
 *
 * <p>This is the shape of a course launch, and it is where shared bookkeeping shows up: if the
 * reporting counters are updated by read-then-write, two enrolments racing on the same course make
 * one of them fail for a reason that has nothing to do with the learner.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class ConcurrentEnrolmentTest {

    private static final int LEARNERS = 12;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;

    @Test
    void a_dozen_learners_enrolling_at_once_all_get_in_and_are_all_counted() throws Exception {
        String courseId = publishFreeCourse();
        List<String> tokens = registerLearners();

        ExecutorService pool = Executors.newFixedThreadPool(LEARNERS);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();

        for (String token : tokens) {
            results.add(pool.submit(() -> {
                startTogether.await();
                return (HttpStatus) http.exchange("/api/v1/enrollments", HttpMethod.POST,
                        new HttpEntity<>("{\"courseId\":\"" + courseId + "\"}", headers(token)),
                        JsonNode.class).getStatusCode();
            }));
        }
        startTogether.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> result : results) {
            statuses.add(result.get());
        }

        assertThat(statuses)
                .as("every learner should get in; a failure here is the platform's bookkeeping, not theirs")
                .containsOnly(HttpStatus.CREATED);

        JsonNode metrics = adminGet("/api/v1/analytics/courses/" + courseId);
        assertThat(metrics.get("enrollments").asLong())
                .as("no enrolment may be lost from the count either")
                .isEqualTo(LEARNERS);
    }

    private String publishFreeCourse() {
        String instructor = "tess-" + UUID.randomUUID() + "@example.com";
        register(instructor, "Tess Teacher", "INSTRUCTOR");
        String token = login(instructor);

        JsonNode course = http.exchange("/api/v1/courses", HttpMethod.POST, new HttpEntity<>("""
                {"title":"Launch day %s","summary":"Free and popular",
                 "price":0.00,"currency":"USD","level":"BEGINNER"}
                """.formatted(UUID.randomUUID()), headers(token)), JsonNode.class).getBody();
        String courseId = course.get("id").asText();

        http.exchange("/api/v1/courses/" + courseId + "/lessons", HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"Only lesson\",\"durationMinutes\":10}", headers(token)),
                JsonNode.class);
        http.exchange("/api/v1/courses/" + courseId + "/publish", HttpMethod.POST,
                new HttpEntity<>(null, headers(token)), JsonNode.class);
        return courseId;
    }

    private List<String> registerLearners() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < LEARNERS; i++) {
            String email = "rush-" + UUID.randomUUID() + "@example.com";
            register(email, "Learner " + i, "STUDENT");
            tokens.add(login(email));
        }
        return tokens;
    }

    private void register(String email, String name, String role) {
        http.exchange("/api/v1/auth/register", HttpMethod.POST, new HttpEntity<>("""
                {"email":"%s","password":"hunter2024","fullName":"%s","role":"%s"}
                """.formatted(email, name, role), headers(null)), JsonNode.class);
    }

    private String login(String email) {
        return http.exchange("/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>("""
                {"email":"%s","password":"hunter2024"}
                """.formatted(email), headers(null)), JsonNode.class).getBody().get("accessToken").asText();
    }

    private JsonNode adminGet(String path) {
        String admin = jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN).accessToken();
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(admin)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}

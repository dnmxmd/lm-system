package com.lms.analytics;

import com.lms.analytics.dto.PlatformOverviewResponse;
import com.lms.analytics.service.AnalyticsService;
import com.lms.common.security.JwtService;
import com.lms.common.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform roll-up runs a multi-column aggregate, which is the kind of query that works in
 * review and throws at runtime. These tests call it for real, with and without data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("schema")
class PlatformOverviewTest {

    @Autowired
    private AnalyticsService analytics;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;

    @Test
    void the_overview_can_be_computed() {
        PlatformOverviewResponse overview = analytics.overview();

        assertThat(overview).isNotNull();
        assertThat(overview.enrollments()).isNotNegative();
        assertThat(overview.completionRatePercent()).isBetween(0, 100);
    }

    @Test
    void an_admin_can_read_the_overview_over_http() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), "root@example.com", Role.ADMIN)
                .accessToken());

        ResponseEntity<JsonNode> response = http.exchange("/api/v1/analytics/overview", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().has("enrollments")).isTrue();
        assertThat(response.getBody().has("revenue")).isTrue();
    }

    @Test
    void an_instructor_is_not_an_admin_for_the_platform_wide_report() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), "tess@example.com", Role.INSTRUCTOR)
                .accessToken());

        ResponseEntity<String> response = http.exchange("/api/v1/analytics/overview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

package com.lms.gateway;

import com.lms.common.security.JwtService;
import com.lms.common.security.Role;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rate limiting against real Redis.
 *
 * <p>The questions are who a request counts against and whether the counter ever stops counting.
 * Keying on the raw Authorization header answered both badly: a second login was a second
 * allowance, and a forged token was an allowance of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("realinfra")
@TestPropertySource(properties = {
        "lms.rate-limit.enabled=true",
        "lms.rate-limit.requests=5",
        "lms.rate-limit.window=PT2S"
})
class RateLimitTest {

    private static final String PATH = "/api/v1/courses";

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeAll
    static void requireRedis() {
        assumeTrue(reachable("localhost", 6379), "Redis is not running on localhost:6379");
        assumeTrue(reachable("localhost", 5432), "PostgreSQL is not running on localhost:5432");
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException unreachable) {
            return false;
        }
    }

    @BeforeEach
    void clearCounters() {
        Set<String> keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private HttpStatus call(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return (HttpStatus) http.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode();
    }

    private String tokenFor(UUID userId) {
        return jwtService.issue(userId, "rate-" + userId + "@example.com", Role.STUDENT).accessToken();
    }

    @Test
    void two_tokens_for_the_same_account_share_one_quota() {
        UUID account = UUID.randomUUID();
        String firstSession = tokenFor(account);
        String secondSession = tokenFor(account);

        for (int i = 0; i < 5; i++) {
            assertThat(call(firstSession)).isEqualTo(HttpStatus.OK);
        }

        assertThat(call(secondSession))
                .as("signing in again must not hand out a fresh allowance")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void different_accounts_do_not_share_a_quota() {
        String one = tokenFor(UUID.randomUUID());
        String two = tokenFor(UUID.randomUUID());

        for (int i = 0; i < 5; i++) {
            assertThat(call(one)).isEqualTo(HttpStatus.OK);
        }

        assertThat(call(two)).as("one busy learner must not throttle another").isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalid_and_missing_credentials_share_the_anonymous_quota() {
        // Four anonymous, then a request carrying rubbish: all the same caller as far as the
        // limiter is concerned, because none of them proved who they are.
        for (int i = 0; i < 4; i++) {
            assertThat(call(null)).isEqualTo(HttpStatus.OK);
        }
        assertThat(call("not-a-real-token")).isEqualTo(HttpStatus.OK);

        assertThat(call("neither-is-this"))
                .as("a forged token must not buy its own allowance")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void the_counter_expires_at_the_end_of_the_window() throws Exception {
        String token = tokenFor(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            call(token);
        }
        assertThat(call(token)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // The window is two seconds for this test.
        Thread.sleep(2300);

        assertThat(call(token))
                .as("the allowance has to come back, or a caller is locked out for good")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void the_counter_is_created_with_an_expiry_in_one_step() {
        String token = tokenFor(UUID.randomUUID());
        call(token);

        String key = redis.keys("ratelimit:user:*").stream().findFirst().orElseThrow();
        Long ttl = redis.getExpire(key);

        assertThat(ttl)
                .as("a counter with no expiry locks its caller out until somebody deletes it")
                .isNotNull()
                .isGreaterThan(0L);
    }
}

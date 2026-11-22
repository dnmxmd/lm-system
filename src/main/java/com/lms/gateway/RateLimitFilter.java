package com.lms.gateway;

import com.lms.common.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A fixed-window counter in Redis, keyed on the bearer token when there is one and on the client
 * address otherwise, so one anonymous caller cannot spend everybody's budget.
 *
 * <p>If Redis is unreachable the request is let through. Losing the cache should not take the whole
 * API down with it.
 * shortcut: fixed window, so a caller can land 2x the limit across a window boundary. Move to a
 * sliding window or a token bucket if that burst ever matters.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redis, RateLimitProperties properties, ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long used = countRequest(callerKey(request));
        if (used > properties.requests()) {
            reject(request, response);
            return;
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(properties.requests()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, properties.requests() - used)));
        chain.doFilter(request, response);
    }

    /** @return how many requests this caller has made in the current window, or 0 if Redis is down. */
    private long countRequest(String key) {
        try {
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, properties.window());
            }
            return used == null ? 0 : used;
        } catch (DataAccessException ex) {
            log.warn("Rate limiting is off: Redis is unreachable ({})", ex.getMessage());
            return 0;
        }
    }

    private String callerKey(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            // The token's hash, not its claims: this filter runs before authentication, and a
            // forged token still buys the forger only their own bucket.
            return KEY_PREFIX + "token:" + Integer.toHexString(authorization.hashCode());
        }
        return KEY_PREFIX + "ip:" + request.getRemoteAddr();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(properties.window().toSeconds()));
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(429, "TOO_MANY_REQUESTS",
                "Too many requests; try again shortly", request.getRequestURI()));
    }
}

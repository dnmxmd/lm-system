package com.lms.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.common.security.JwtService;
import com.lms.common.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * A fixed-window counter in Redis.
 *
 * <p>The bucket is chosen by <em>verified</em> identity. An authenticated caller is counted against
 * their user id, so two tokens for the same account share one quota and a new token is not a fresh
 * allowance. Anything that is not a valid token — missing, expired, forged — is anonymous and
 * counted against the client address, so an attacker cannot mint themselves a private bucket by
 * sending junk in the header.
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
    private static final String BEARER = "Bearer ";

    /**
     * Counting and expiry in one round trip.
     *
     * <p>Done as INCR followed by a separate EXPIRE, a process dying between the two leaves a key
     * with no expiry at all — the caller is then locked out until somebody deletes it by hand.
     * The script makes the pair atomic, and sets the expiry only on the call that creates the key
     * so the window does not slide forward on every request.
     */
    private static final RedisScript<Long> COUNT_IN_WINDOW = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redis, RateLimitProperties properties,
                           JwtService jwtService, ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.jwtService = jwtService;
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

    /** @return requests made in the current window, or 0 when Redis cannot be reached. */
    private long countRequest(String key) {
        try {
            Long used = redis.execute(COUNT_IN_WINDOW, List.of(key),
                    String.valueOf(properties.window().toMillis()));
            return used == null ? 0 : used;
        } catch (DataAccessException ex) {
            log.warn("Rate limiting is off: Redis is unreachable ({})", ex.getMessage());
            return 0;
        }
    }

    /**
     * The account for a valid token, the client address otherwise.
     *
     * <p>The token is verified here rather than trusted: keying on the raw header gave every token
     * its own allowance, so the same account could have as many quotas as it had sessions, and an
     * invalid token escaped the anonymous quota entirely.
     */
    private String callerKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            var user = jwtService.verify(header.substring(BEARER.length()));
            if (user.isPresent()) {
                return KEY_PREFIX + "user:" + user.get().id();
            }
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

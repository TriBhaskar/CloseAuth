package com.anterka.closeauthbackend.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A minimal Redis-backed fixed-window rate limiter (Stage 6b-i) — the infrastructure the one-time-token flows use for
 * issuance throttling (anti email-bombing / enumeration) and the mandatory per-target attempt-lockout that protects
 * low-entropy numeric codes. Reuses the existing {@code closeauth.redis.rate-limit} operational config.
 *
 * <p>Implementation: {@code INCR} the window key, set {@code EXPIRE} on the first hit; allow while the count is within
 * the limit.
 *
 * <p><b>Fail-open</b> on Redis unavailability (allow + loud ERROR), consistent with the platform's Redis posture: a
 * Redis blip must not lock every user out of registration/reset. The primitive's own controls (atomic single-use +
 * short expiry) remain the primary defense; the limiter is defense-in-depth. This trade-off is deliberate and logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * Records one hit against {@code key}'s window and returns whether it is still within {@code limit}.
     *
     * @return {@code true} if allowed (within limit, or Redis is down → fail-open); {@code false} if the limit is exceeded
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count == null || count <= limit;
        } catch (RuntimeException redisDown) {
            log.error("Redis unavailable — rate limiter FAIL-OPEN for key '{}' (throttling degraded; the token "
                    + "primitive's single-use + short expiry remain the primary controls).", key, redisDown);
            return true;
        }
    }

    /**
     * Read-only variant of {@link #tryAcquire}: reports whether {@code key} is already at or over {@code limit}
     * WITHOUT recording a hit (no {@code INCR}, no {@code EXPIRE}, never creates the key). Callers that only want
     * to count specific outcomes (e.g. login: only failed attempts should consume budget, not successes) use this
     * to gate, then call {@link #tryAcquire} themselves on whichever branch should actually record.
     *
     * @return {@code true} if the recorded count is already {@code >= limit}; {@code false} if under the limit,
     *         the key doesn't exist yet, or Redis is unavailable (fail-open, matching {@link #tryAcquire}'s posture)
     */
    public boolean isLimited(String key, int limit) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value != null && Long.parseLong(value) >= limit;
        } catch (RuntimeException redisDown) {
            log.error("Redis unavailable — rate limiter FAIL-OPEN (read) for key '{}'.", key, redisDown);
            return false;
        }
    }
}

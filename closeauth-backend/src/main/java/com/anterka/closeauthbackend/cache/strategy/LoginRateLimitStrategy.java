package com.anterka.closeauthbackend.cache.strategy;

import com.anterka.closeauthbackend.common.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rate limit strategy for user login attempts (keyed by client IP).
 * Complements the per-account lockout to mitigate distributed brute-force.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimitStrategy implements RateLimitStrategy {

    public static final String ACTION = "login";

    private final RedisProperties redisProperties;

    @Override
    public String getAction() {
        return ACTION;
    }

    @Override
    public int getMaxAttempts() {
        return redisProperties.getRateLimit().getLogin();
    }

    @Override
    public long getWindowSeconds() {
        return redisProperties.getRateLimit().getWindowMinutes() * 60L;
    }
}


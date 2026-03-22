package com.anterka.closeauthbackend.cache.strategy;

import com.anterka.closeauthbackend.common.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rate limit strategy for password reset requests.
 */
@Component
@RequiredArgsConstructor
public class ResetPasswordRateLimitStrategy implements RateLimitStrategy {

    public static final String ACTION = "reset_password";

    private final RedisProperties redisProperties;

    @Override
    public String getAction() {
        return ACTION;
    }

    @Override
    public int getMaxAttempts() {
        return redisProperties.getRateLimit().getResetPassword();
    }

    @Override
    public long getWindowSeconds() {
        return redisProperties.getRateLimit().getWindowMinutes() * 60L;
    }
}

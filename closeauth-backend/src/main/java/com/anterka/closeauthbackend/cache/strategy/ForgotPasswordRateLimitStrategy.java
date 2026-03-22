package com.anterka.closeauthbackend.cache.strategy;

import com.anterka.closeauthbackend.common.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rate limit strategy for forgot password requests.
 */
@Component
@RequiredArgsConstructor
public class ForgotPasswordRateLimitStrategy implements RateLimitStrategy {

    public static final String ACTION = "forgot_password";

    private final RedisProperties redisProperties;

    @Override
    public String getAction() {
        return ACTION;
    }

    @Override
    public int getMaxAttempts() {
        return redisProperties.getRateLimit().getForgotPassword();
    }

    @Override
    public long getWindowSeconds() {
        return redisProperties.getRateLimit().getWindowMinutes() * 60L;
    }
}

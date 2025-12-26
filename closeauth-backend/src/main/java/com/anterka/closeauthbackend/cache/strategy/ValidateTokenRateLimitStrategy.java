package com.anterka.closeauthbackend.cache.strategy;

import com.anterka.closeauthbackend.common.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rate limit strategy for token validation requests.
 */
@Component
@RequiredArgsConstructor
public class ValidateTokenRateLimitStrategy implements RateLimitStrategy {

    public static final String ACTION = "validate_token";

    private final RedisProperties redisProperties;

    @Override
    public String getAction() {
        return ACTION;
    }

    @Override
    public int getMaxAttempts() {
        return redisProperties.getRateLimit().getValidateToken();
    }

    @Override
    public long getWindowSeconds() {
        return redisProperties.getRateLimit().getWindowMinutes() * 60L;
    }
}

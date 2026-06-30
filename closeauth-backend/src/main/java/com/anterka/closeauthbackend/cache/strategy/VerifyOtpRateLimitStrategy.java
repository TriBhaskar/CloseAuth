package com.anterka.closeauthbackend.cache.strategy;

import com.anterka.closeauthbackend.common.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rate limit strategy for OTP verification attempts (keyed by email).
 * Prevents brute-forcing the numeric OTP within its validity window.
 */
@Component
@RequiredArgsConstructor
public class VerifyOtpRateLimitStrategy implements RateLimitStrategy {

    public static final String ACTION = "verify_otp";

    private final RedisProperties redisProperties;

    @Override
    public String getAction() {
        return ACTION;
    }

    @Override
    public int getMaxAttempts() {
        return redisProperties.getRateLimit().getVerifyOtp();
    }

    @Override
    public long getWindowSeconds() {
        return redisProperties.getRateLimit().getWindowMinutes() * 60L;
    }
}


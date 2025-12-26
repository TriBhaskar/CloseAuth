package com.anterka.closeauthbackend.cache.strategy;

/**
 * Default rate limit strategy for unknown actions.
 */
public class DefaultRateLimitStrategy implements RateLimitStrategy {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long DEFAULT_WINDOW_SECONDS = 15 * 60L; // 15 minutes

    private final String action;

    public DefaultRateLimitStrategy() {
        this.action = "default";
    }

    public DefaultRateLimitStrategy(String action) {
        this.action = action;
    }

    @Override
    public String getAction() {
        return action;
    }

    @Override
    public int getMaxAttempts() {
        return DEFAULT_MAX_ATTEMPTS;
    }

    @Override
    public long getWindowSeconds() {
        return DEFAULT_WINDOW_SECONDS;
    }
}

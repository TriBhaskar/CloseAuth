package com.anterka.closeauthbackend.cache.strategy;

/**
 * Strategy interface for rate limiting different actions.
 * Each implementation defines the limits for a specific action.
 */
public interface RateLimitStrategy {

    /**
     * Returns the action name this strategy handles.
     */
    String getAction();

    /**
     * Returns the maximum number of attempts allowed within the window.
     */
    int getMaxAttempts();

    /**
     * Returns the rate limit window duration in seconds.
     */
    long getWindowSeconds();
}

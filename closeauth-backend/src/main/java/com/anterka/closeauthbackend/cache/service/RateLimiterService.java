package com.anterka.closeauthbackend.cache.service;

import com.anterka.closeauthbackend.cache.repository.RateLimitRepository;
import com.anterka.closeauthbackend.cache.strategy.RateLimitStrategy;
import com.anterka.closeauthbackend.cache.strategy.RateLimitStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for rate limiting various actions using the Strategy pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final RateLimitRepository rateLimitRepository;
    private final RateLimitStrategyFactory strategyFactory;

    /**
     * Checks if the action is rate limited for the given identifier.
     * <p>
     * The counter is incremented atomically (server-side INCR + EXPIRE) so that
     * concurrent requests cannot race past the configured limit.
     *
     * @param action The action type (e.g., "forgot_password", "reset_password")
     * @param identifier The unique identifier (e.g., IP address, user ID)
     * @return true if rate limited, false otherwise
     */
    public boolean isLimited(String action, String identifier) {
        RateLimitStrategy strategy = strategyFactory.getStrategy(action);

        long currentCount = rateLimitRepository.incrementAndGetCount(
                action, identifier, strategy.getWindowSeconds());
        int limit = strategy.getMaxAttempts();

        if (currentCount > limit) {
            log.debug("Rate limit exceeded for action '{}', identifier '{}': {} > {}",
                    action, identifier, currentCount, limit);
            return true;
        }


        return false;
    }

    /**
     * Gets remaining attempts for an action/identifier combination.
     */
    public int getRemainingAttempts(String action, String identifier) {
        RateLimitStrategy strategy = strategyFactory.getStrategy(action);
        int currentCount = rateLimitRepository.getCount(action, identifier);
        return Math.max(0, strategy.getMaxAttempts() - currentCount);
    }
}

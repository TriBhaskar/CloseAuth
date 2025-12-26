package com.anterka.closeauthbackend.cache.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;

/**
 * Redis repository for rate limiting counters.
 */
@Repository
public class RateLimitRepository extends BaseRedisRepository {

    private static final String KEY_PREFIX = "rate_limit:";

    public RateLimitRepository(JedisPooled jedisClient) {
        super(jedisClient);
    }

    @Override
    protected String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Builds a composite key for rate limiting.
     * Format: rate_limit:{action}:{identifier}
     */
    public String buildRateLimitKey(String action, String identifier) {
        return action + ":" + identifier;
    }

    /**
     * Gets the current count for a rate limit key.
     */
    public int getCount(String action, String identifier) {
        String key = buildRateLimitKey(action, identifier);
        return get(key)
                .map(Integer::parseInt)
                .orElse(0);
    }

    /**
     * Initializes a rate limit counter with TTL.
     */
    public void initializeCounter(String action, String identifier, long windowSeconds) {
        String key = buildRateLimitKey(action, identifier);
        saveWithTtl(key, "1", windowSeconds);
    }

    /**
     * Increments a rate limit counter.
     */
    public long incrementCounter(String action, String identifier) {
        String key = buildRateLimitKey(action, identifier);
        return increment(key);
    }

    /**
     * Checks if a rate limit counter exists.
     */
    public boolean counterExists(String action, String identifier) {
        String key = buildRateLimitKey(action, identifier);
        return exists(key);
    }
}


package com.anterka.closeauthbackend.cache.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;

import java.util.List;

/**
 * Redis repository for rate limiting counters.
 */
@Repository
public class RateLimitRepository extends BaseRedisRepository {

    private static final String KEY_PREFIX = "rate_limit:";

    /**
     * Atomically increments the counter and, on the first hit, sets its TTL.
     * Running this as a single server-side script removes the check-then-act
     * race condition (TOCTOU) that a separate GET + INCR would have.
     * Returns the counter's new value.
     */
    private static final String INCREMENT_WITH_TTL_LUA =
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current";

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
     * Atomically increments the rate limit counter for the given action/identifier,
     * setting the window TTL on first use, and returns the new count.
     */
    public long incrementAndGetCount(String action, String identifier, long windowSeconds) {
        String fullKey = buildKey(buildRateLimitKey(action, identifier));
        Object result = jedisClient.eval(
                INCREMENT_WITH_TTL_LUA,
                List.of(fullKey),
                List.of(String.valueOf(windowSeconds)));
        return ((Number) result).longValue();
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


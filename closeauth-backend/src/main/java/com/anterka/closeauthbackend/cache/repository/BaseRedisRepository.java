package com.anterka.closeauthbackend.cache.repository;

import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/**
 * Abstract base class for Redis repository operations.
 * Provides common CRUD operations with consistent key management.
 */
public abstract class BaseRedisRepository {

    protected final JedisPooled jedisClient;

    protected BaseRedisRepository(JedisPooled jedisClient) {
        this.jedisClient = jedisClient;
    }

    /**
     * Returns the key prefix for this repository.
     * Example: "otp_", "registration_", "rate_limit:"
     */
    protected abstract String getKeyPrefix();

    /**
     * Builds the full Redis key from the identifier.
     */
    protected String buildKey(String identifier) {
        return getKeyPrefix() + identifier;
    }

    /**
     * Saves a string value with TTL.
     */
    public void saveWithTtl(String identifier, String value, long ttlSeconds) {
        jedisClient.setex(buildKey(identifier), ttlSeconds, value);
    }

    /**
     * Gets a string value.
     */
    public Optional<String> get(String identifier) {
        return Optional.ofNullable(jedisClient.get(buildKey(identifier)));
    }

    /**
     * Deletes a value.
     */
    public void delete(String identifier) {
        jedisClient.del(buildKey(identifier));
    }

    /**
     * Checks if a key exists.
     */
    public boolean exists(String identifier) {
        return jedisClient.exists(buildKey(identifier));
    }

    /**
     * Increments a counter value.
     */
    public long increment(String identifier) {
        return jedisClient.incr(buildKey(identifier));
    }

    /**
     * Sets expiration on an existing key.
     */
    public void expire(String identifier, long seconds) {
        jedisClient.expire(buildKey(identifier), seconds);
    }
}


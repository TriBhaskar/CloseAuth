package com.anterka.closeauthbackend.cache.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/**
 * Redis repository for password reset token storage.
 */
@Repository
public class PasswordResetTokenRepository extends BaseRedisRepository {

    private static final String KEY_PREFIX = "password_reset:";

    public PasswordResetTokenRepository(JedisPooled jedisClient) {
        super(jedisClient);
    }

    @Override
    protected String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Saves a password reset token with TTL.
     * @param token The reset token
     * @param userId The user ID associated with this token
     * @param ttlSeconds Time to live in seconds
     */
    public void saveToken(String token, String userId, long ttlSeconds) {
        saveWithTtl(token, userId, ttlSeconds);
    }

    /**
     * Retrieves the user ID associated with a reset token.
     */
    public Optional<String> getUserIdByToken(String token) {
        return get(token);
    }

    /**
     * Invalidates a password reset token.
     */
    public void invalidateToken(String token) {
        delete(token);
    }

    /**
     * Checks if a token is valid (exists).
     */
    public boolean isTokenValid(String token) {
        return exists(token);
    }
}


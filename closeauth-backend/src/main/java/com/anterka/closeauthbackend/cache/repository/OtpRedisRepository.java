package com.anterka.closeauthbackend.cache.repository;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/**
 * Redis repository for OTP (One-Time Password) storage.
 */
@Repository
public class OtpRedisRepository extends BaseRedisRepository {

    private static final String KEY_PREFIX = "otp_";

    public OtpRedisRepository(JedisPooled jedisClient) {
        super(jedisClient);
    }

    @Override
    protected String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Saves an OTP with the specified TTL.
     */
    public void saveOtp(String email, String otp, long ttlSeconds) {
        saveWithTtl(email, otp, ttlSeconds);
    }

    /**
     * Retrieves an OTP for the given email.
     */
    public Optional<String> getOtp(String email) {
        return get(email);
    }

    /**
     * Deletes an OTP for the given email.
     */
    public void deleteOtp(String email) {
        delete(email);
    }
}


package com.anterka.closeauthbackend.cache.repository;

import com.google.gson.Gson;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/**
 * Redis repository for registration data cache.
 * Uses JSON storage for complex objects.
 */
@Repository
public class RegistrationCacheRepository extends BaseRedisRepository {

    private static final String KEY_PREFIX = "registration_";

    private final Gson gson;

    public RegistrationCacheRepository(JedisPooled jedisClient, Gson gson) {
        super(jedisClient);
        this.gson = gson;
    }

    @Override
    protected String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Saves registration data as JSON with TTL.
     */
    public <T> void saveRegistrationData(String email, T data, long ttlSeconds) {
        String key = buildKey(email);
        jedisClient.jsonSet(key, gson.toJson(data));
        jedisClient.expire(key, ttlSeconds);
    }

    /**
     * Retrieves registration data and deserializes from JSON.
     */
    public <T> Optional<T> getRegistrationData(String email, Class<T> clazz) {
        String key = buildKey(email);
        Object jsonResult = jedisClient.jsonGet(key);
        if (jsonResult == null) {
            return Optional.empty();
        }
        String jsonStr = gson.toJson(jsonResult);
        return Optional.ofNullable(gson.fromJson(jsonStr, clazz));
    }

    /**
     * Checks if registration exists.
     */
    public boolean registrationExists(String email) {
        return exists(email);
    }

    /**
     * Deletes registration data.
     */
    public void deleteRegistration(String email) {
        delete(email);
    }
}


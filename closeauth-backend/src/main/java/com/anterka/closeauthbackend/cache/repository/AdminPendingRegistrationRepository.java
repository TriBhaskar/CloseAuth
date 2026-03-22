package com.anterka.closeauthbackend.cache.repository;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis repository for admin pending registration approvals.
 * Uses key pattern: admin_pending_{clientId}_{email}
 */
@Repository
@Slf4j
public class AdminPendingRegistrationRepository extends BaseRedisRepository {

    private static final String KEY_PREFIX = "admin_pending_";
    private final Gson gson;

    public AdminPendingRegistrationRepository(JedisPooled jedisClient, Gson gson) {
        super(jedisClient);
        this.gson = gson;
    }

    @Override
    protected String getKeyPrefix() {
        return KEY_PREFIX;
    }

    /**
     * Builds key for client-specific pending registration.
     * Format: admin_pending_{clientId}_{email}
     */
    private String buildClientKey(String clientId, String email) {
        return KEY_PREFIX + clientId + "_" + email;
    }

    /**
     * Saves pending registration data for admin approval.
     */
    public void savePendingApproval(String clientId, String email, RegistrationData data, long ttlSeconds) {
        String key = buildClientKey(clientId, email);
        String json = gson.toJson(data);
        jedisClient.setex(key, ttlSeconds, json);
        log.debug("Saved pending approval for client {} email {}", clientId, email);
    }

    /**
     * Gets pending registration data.
     */
    public Optional<RegistrationData> getPendingApproval(String clientId, String email) {
        String key = buildClientKey(clientId, email);
        String json = jedisClient.get(key);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(gson.fromJson(json, RegistrationData.class));
    }

    /**
     * Deletes pending registration data.
     */
    public void deletePendingApproval(String clientId, String email) {
        String key = buildClientKey(clientId, email);
        jedisClient.del(key);
        log.debug("Deleted pending approval for client {} email {}", clientId, email);
    }

    /**
     * Gets all pending approvals for a specific client.
     * Uses SCAN to iterate through matching keys.
     */
    public List<RegistrationData> getPendingApprovalsForClient(String clientId) {
        List<RegistrationData> pendingApprovals = new ArrayList<>();
        String pattern = KEY_PREFIX + clientId + "_*";

        String cursor = "0";
        ScanParams scanParams = new ScanParams().match(pattern).count(100);

        do {
            ScanResult<String> scanResult = jedisClient.scan(cursor, scanParams);
            cursor = scanResult.getCursor();

            for (String key : scanResult.getResult()) {
                String json = jedisClient.get(key);
                if (json != null) {
                    try {
                        RegistrationData data = gson.fromJson(json, RegistrationData.class);
                        pendingApprovals.add(data);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize pending approval from key {}: {}", key, e.getMessage());
                    }
                }
            }
        } while (!"0".equals(cursor));

        return pendingApprovals;
    }

    /**
     * Checks if a pending approval exists.
     */
    public boolean pendingApprovalExists(String clientId, String email) {
        String key = buildClientKey(clientId, email);
        return jedisClient.exists(key);
    }

    /**
     * Gets count of pending approvals for a client.
     */
    public long countPendingApprovals(String clientId) {
        String pattern = KEY_PREFIX + clientId + "_*";
        long count = 0;

        String cursor = "0";
        ScanParams scanParams = new ScanParams().match(pattern).count(100);

        do {
            ScanResult<String> scanResult = jedisClient.scan(cursor, scanParams);
            cursor = scanResult.getCursor();
            count += scanResult.getResult().size();
        } while (!"0".equals(cursor));

        return count;
    }
}


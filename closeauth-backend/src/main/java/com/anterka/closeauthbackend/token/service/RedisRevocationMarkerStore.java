package com.anterka.closeauthbackend.token.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Redis-backed {@link RevocationMarkerStore} (Jedis client, via Boot's auto-configured {@link StringRedisTemplate}).
 *
 * <p>Key schema:
 * <ul>
 *   <li>User marker: {@code revoked:t:{tenantId}:user:{userId}} (tenant in the key keeps markers tenant-navigable)</li>
 *   <li>Tenant marker: {@code revoked:tenant:{tenantId}} (one write revokes the whole tenant, checked alongside the
 *       user marker — cheaper than fanning out a per-user marker for every user)</li>
 * </ul>
 * Value: the revocation time as epoch <b>seconds</b> (to compare directly with JWT {@code iat}). TTL: the max
 * access-token TTL — a marker only needs to outlive the tokens it suppresses (see report §TTL).
 *
 * <p><b>Fail-open</b> (§7.3 operational property): if Redis is unavailable, reads return empty (skip the revocation
 * check → the token is judged by its JWT validity = the pre-existing 5-minute window), and writes log loudly. A
 * Redis blip must NOT become a platform-wide auth outage; the trade-off is a temporarily-degraded revocation posture,
 * which is why every failure is logged at ERROR.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisRevocationMarkerStore implements RevocationMarkerStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void revokeUser(UUID tenantId, UUID userId, Instant at, Duration ttl) {
        write(userKey(tenantId, userId), at, ttl);
    }

    @Override
    public void revokeTenant(UUID tenantId, Instant at, Duration ttl) {
        write(tenantKey(tenantId), at, ttl);
    }

    @Override
    public OptionalLong userRevocationEpochSeconds(UUID tenantId, UUID userId) {
        return read(userKey(tenantId, userId));
    }

    @Override
    public OptionalLong tenantRevocationEpochSeconds(UUID tenantId) {
        return read(tenantKey(tenantId));
    }

    private void write(String key, Instant at, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, Long.toString(at.getEpochSecond()), ttl);
        } catch (RuntimeException redisDown) {
            log.error("Redis unavailable — revocation marker '{}' NOT written. Revocation was NOT recorded; affected "
                    + "tokens will remain valid until they expire on their own (within the access-token TTL).", key, redisDown);
        }
    }

    private OptionalLong read(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
        } catch (RuntimeException redisDown) {
            // FAIL OPEN: degrade to plain JWT validity (the pre-existing 5-minute window). Loud alarm for ops.
            log.error("Redis unavailable — skipping revocation check for '{}' (FAIL-OPEN: revocation list degraded; "
                    + "tokens are validated by JWT claims only until Redis recovers).", key, redisDown);
            return OptionalLong.empty();
        }
    }

    private String userKey(UUID tenantId, UUID userId) {
        return "revoked:t:" + tenantId + ":user:" + userId;
    }

    private String tenantKey(UUID tenantId) {
        return "revoked:tenant:" + tenantId;
    }
}

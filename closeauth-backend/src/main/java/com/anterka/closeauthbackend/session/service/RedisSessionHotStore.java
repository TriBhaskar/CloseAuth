package com.anterka.closeauthbackend.session.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed {@link SessionHotStore} (Jedis client, via Boot's auto-configured {@link StringRedisTemplate}).
 *
 * <p>Key: {@code {closeauth.session.redis-key-prefix}{sessionKey}} (default {@code closeauth:authsession:{sessionKey}}).
 * Value: the {@link SessionHotState} as a compact JSON string produced by a plain {@link ObjectMapper} — the state is
 * intentionally all primitives/Strings/epoch-millis longs, so it round-trips with no default typing and no temporal
 * modules (the Stage 4b-ii D1 lesson: never store types that can't be read back). TTL: aligned to the earliest of the
 * idle/absolute expiry, so an idle-dead session is evicted automatically and the hot entry never outlives the
 * absolute cap.
 *
 * <p><b>Fail toward re-auth:</b> if Redis is unavailable or the value is unreadable, {@link #find} returns empty —
 * the caller treats that as "no session" and re-authenticates. Writes propagate on failure (a session that cannot be
 * persisted to the shared store was not truly established). This is the deliberate opposite of the 4b-ii revocation
 * list's fail-open: the hot store IS the session's source of truth.
 */
@Component
@Slf4j
public class RedisSessionHotStore implements SessionHotStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisSessionHotStore(StringRedisTemplate redisTemplate, CloseAuthProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper(); // plain mapper — the state is trivially serializable by design
        this.keyPrefix = properties.getSession().getRedisKeyPrefix();
    }

    @Override
    public void save(SessionHotState state, Duration ttl) {
        String json;
        try {
            json = objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            // Cannot happen for our all-primitive state; treat as a programming error rather than silently proceed.
            throw new IllegalStateException("Failed to serialize session hot state for " + state.sessionKey(), e);
        }
        redisTemplate.opsForValue().set(key(state.sessionKey()), json, ttl);
    }

    @Override
    public Optional<SessionHotState> find(String sessionKey) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key(sessionKey));
        } catch (RuntimeException redisDown) {
            // Fail toward re-auth: we cannot verify the session, so report "no session".
            log.error("Redis unavailable — cannot read session '{}'; treating as no session (forces re-authentication).",
                    sessionKey, redisDown);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SessionHotState.class));
        } catch (JsonProcessingException unreadable) {
            log.error("Unreadable session hot state for '{}' — treating as no session.", sessionKey, unreadable);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String sessionKey) {
        try {
            redisTemplate.delete(key(sessionKey));
        } catch (RuntimeException redisDown) {
            // Best-effort: the ledger row is still marked revoked and the entry has a TTL, so it cannot outlive the
            // absolute cap even if this delete is lost.
            log.error("Redis unavailable — could not delete session '{}' from the hot store (it still carries a TTL "
                    + "and the ledger row is marked revoked).", sessionKey, redisDown);
        }
    }

    private String key(String sessionKey) {
        return keyPrefix + sessionKey;
    }
}

package com.anterka.closeauthbackend.token.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis marker store: key schema, epoch-seconds value, TTL, and — critically — <b>fail-open</b>
 * when Redis is unavailable (reads return empty so introspection degrades to plain JWT validity; writes/reads never
 * throw). Uses a mocked {@link StringRedisTemplate} so no Redis is required.
 */
class RedisRevocationMarkerStoreTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private ValueOperations<String, String> valueOps;
    private RedisRevocationMarkerStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new RedisRevocationMarkerStore(redisTemplate);
    }

    @Test
    void writeUserMarkerUsesTenantKeyEpochSecondsValueAndTtl() {
        store.revokeUser(tenant, user, Instant.ofEpochSecond(1_700_000_000L), Duration.ofMinutes(5));
        verify(valueOps).set("revoked:t:" + tenant + ":user:" + user, "1700000000", Duration.ofMinutes(5));
    }

    @Test
    void writeTenantMarkerUsesTenantKey() {
        store.revokeTenant(tenant, Instant.ofEpochSecond(1_700_000_000L), Duration.ofMinutes(5));
        verify(valueOps).set("revoked:tenant:" + tenant, "1700000000", Duration.ofMinutes(5));
    }

    @Test
    void readParsesEpochSeconds() {
        when(valueOps.get("revoked:t:" + tenant + ":user:" + user)).thenReturn("1700000000");
        assertThat(store.userRevocationEpochSeconds(tenant, user)).hasValue(1_700_000_000L);
    }

    @Test
    void missingMarkerReturnsEmpty() {
        when(valueOps.get(any())).thenReturn(null);
        assertThat(store.userRevocationEpochSeconds(tenant, user)).isEmpty();
    }

    @Test
    void readFailsOpenWhenRedisIsDown() {
        when(valueOps.get(any())).thenThrow(new RedisConnectionFailureException("redis down"));
        // FAIL OPEN: no exception, treat as "no marker" (token judged by JWT validity).
        assertThat(store.userRevocationEpochSeconds(tenant, user)).isEmpty();
        assertThat(store.tenantRevocationEpochSeconds(tenant)).isEmpty();
    }

    @Test
    void writeSwallowsRedisFailure() {
        doThrow(new QueryTimeoutException("redis slow")).when(valueOps).set(any(), any(), any(Duration.class));
        assertThatCode(() -> store.revokeUser(tenant, user, Instant.now(), Duration.ofMinutes(5)))
                .doesNotThrowAnyException();
    }
}

package com.anterka.closeauthbackend.session.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis session hot store: key schema, TTL pass-through, <b>serialization round-trip</b> (the
 * Stage 4b-ii D1 lesson — what we write must read back), and fail-toward-re-auth on Redis unavailability. Uses a
 * mocked {@link StringRedisTemplate}; the JSON is produced/consumed by the store's real {@link com.fasterxml.jackson.databind.ObjectMapper}.
 */
class RedisSessionHotStoreTest {

    private ValueOperations<String, String> valueOps;
    private StringRedisTemplate redisTemplate;
    private RedisSessionHotStore store;

    private final SessionHotState sample = new SessionHotState(
            UUID.randomUUID().toString(), "sess-key-abc", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            true, 1_700_000_000_000L, 1_700_000_600_000L, 1_700_040_000_000L, "pwd");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new RedisSessionHotStore(redisTemplate, new CloseAuthProperties());
    }

    @Test
    void saveUsesPrefixedKeyAndTtlAndTheValueRoundTrips() {
        store.save(sample, Duration.ofHours(1));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(valueOps).set(keyCaptor.capture(), jsonCaptor.capture(), eq(Duration.ofHours(1)));
        assertThat(keyCaptor.getValue()).isEqualTo("closeauth:authsession:sess-key-abc");

        // Round-trip: feed the exact JSON we wrote back through find() → must reconstruct an equal state.
        when(valueOps.get("closeauth:authsession:sess-key-abc")).thenReturn(jsonCaptor.getValue());
        assertThat(store.find("sess-key-abc")).contains(sample);
    }

    @Test
    void findReturnsEmptyWhenAbsent() {
        when(valueOps.get(any())).thenReturn(null);
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void findFailsTowardReauthWhenRedisIsDown() {
        when(valueOps.get(any())).thenThrow(new RedisConnectionFailureException("redis down"));
        // No throw, and "no session" (→ the caller re-authenticates) — the hot store is the session's source of truth.
        assertThat(store.find("sess-key-abc")).isEmpty();
    }

    @Test
    void deleteSwallowsRedisFailure() {
        when(redisTemplate.delete(any(String.class))).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatCode(() -> store.delete("sess-key-abc")).doesNotThrowAnyException();
    }
}

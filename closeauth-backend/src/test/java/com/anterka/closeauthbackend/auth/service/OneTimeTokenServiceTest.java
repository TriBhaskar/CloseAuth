package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult.FailureReason;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.entity.OneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.repository.OneTimeTokenRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exhaustive unit tests for the one-time-token primitive — the Stage 6b-i security centerpiece. Covers every security
 * property: hash-storage (raw never persisted), atomic single-use (incl. the concurrent race), expiry, tenant-bound,
 * purpose-bound, and enumeration-safe uniform failures. Uses a real generator/hasher (deterministic) with a mocked
 * repository and a fixed clock.
 */
class OneTimeTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private OneTimeTokenRepository repository;
    private OneTimeTokenGenerator generator;
    private OneTimeTokenService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(OneTimeTokenRepository.class);
        generator = new OneTimeTokenGenerator(); // real: deterministic hashing
        service = new OneTimeTokenService(repository, generator, new CloseAuthProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any(OneTimeToken.class))).thenAnswer(inv -> {
            OneTimeToken t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            return t;
        });
    }

    // ---- hash storage (raw never persisted) --------------------------------

    @Test
    void issueStoresHashNeverTheRawSecret_opaque() {
        RawOneTimeToken raw = service.issue(command(OneTimeTokenPurpose.PASSWORD_RESET,
                OneTimeTokenFormat.OPAQUE_LINK, "a@x.com"));

        OneTimeToken saved = captureSaved();
        assertThat(saved.getTokenHash()).isNotEqualTo(raw.rawSecret());       // never the plaintext
        assertThat(saved.getTokenHash()).isEqualTo(generator.hash(raw.rawSecret())); // exactly its hash
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void numericCodeHashIsScopedToTenantPurposeTarget() {
        RawOneTimeToken raw = service.issue(command(OneTimeTokenPurpose.EMAIL_VERIFICATION,
                OneTimeTokenFormat.NUMERIC_CODE, "a@x.com"));

        OneTimeToken saved = captureSaved();
        // The bare-code hash would collide across recipients; the stored hash is scoped, so it differs.
        assertThat(saved.getTokenHash()).isNotEqualTo(generator.hash(raw.rawSecret()));
        assertThat(raw.rawSecret()).hasSize(6).containsOnlyDigits();
    }

    // ---- consume happy path + atomic single-use ----------------------------

    @Test
    void consumeOpaqueHappyPathMarksUsedAtomically() {
        String rawToken = "opaque-token-xyz";
        stubFound(rawToken, token(OneTimeTokenPurpose.MAGIC_LINK, tenantA, false, NOW.plusSeconds(60)));
        when(repository.markUsedIfUnused(any(), eq(NOW))).thenReturn(1); // won the atomic transition

        ConsumeResult result = service.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantA);

        assertThat(result.success()).isTrue();
        assertThat(result.userId()).isEqualTo(userId);
        verify(repository).markUsedIfUnused(any(), eq(NOW));
    }

    @Test
    void secondConcurrentConsumeLosesTheAtomicRace() {
        String rawToken = "opaque-token-xyz";
        stubFound(rawToken, token(OneTimeTokenPurpose.MAGIC_LINK, tenantA, false, NOW.plusSeconds(60)));
        when(repository.markUsedIfUnused(any(), eq(NOW))).thenReturn(0); // a concurrent consumer already won

        ConsumeResult result = service.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantA);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(FailureReason.ALREADY_USED);
    }

    @Test
    void alreadyUsedTokenFails() {
        String rawToken = "opaque-token-xyz";
        stubFound(rawToken, token(OneTimeTokenPurpose.MAGIC_LINK, tenantA, true, NOW.plusSeconds(60))); // used=true
        assertThat(service.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantA).reason())
                .isEqualTo(FailureReason.ALREADY_USED);
    }

    // ---- expiry / tenant / purpose bounds ----------------------------------

    @Test
    void expiredTokenFails() {
        String rawToken = "opaque-token-xyz";
        stubFound(rawToken, token(OneTimeTokenPurpose.MAGIC_LINK, tenantA, false, NOW.minusSeconds(1)));
        assertThat(service.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantA).reason())
                .isEqualTo(FailureReason.EXPIRED);
    }

    @Test
    void tokenIssuedInOneTenantCannotBeConsumedInAnother() {
        String rawToken = "opaque-token-xyz";
        stubFound(rawToken, token(OneTimeTokenPurpose.MAGIC_LINK, tenantA, false, NOW.plusSeconds(60)));
        assertThat(service.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantB).reason())
                .isEqualTo(FailureReason.WRONG_TENANT);
    }

    @Test
    void tokenMintedForOnePurposeIsRejectedForAnother() {
        String rawToken = "opaque-token-xyz";
        stubFound(rawToken, token(OneTimeTokenPurpose.PASSWORD_RESET, tenantA, false, NOW.plusSeconds(60)));
        assertThat(service.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantA).reason())
                .isEqualTo(FailureReason.WRONG_PURPOSE);
    }

    @Test
    void unknownTokenFails() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThat(service.consume("nope", OneTimeTokenPurpose.MAGIC_LINK, tenantA).reason())
                .isEqualTo(FailureReason.NOT_FOUND);
        assertThat(service.consume(null, OneTimeTokenPurpose.MAGIC_LINK, tenantA).reason())
                .isEqualTo(FailureReason.NOT_FOUND);
    }

    // ---- numeric consume + invalidate --------------------------------------

    @Test
    void consumeCodeUsesTheScopedHashLookup() {
        String code = "123456";
        String target = "a@x.com";
        String scopedHash = generator.hash(tenantA + "|EMAIL_VERIFICATION|" + target + "|" + code);
        when(repository.findByTokenHash(scopedHash))
                .thenReturn(Optional.of(token(OneTimeTokenPurpose.EMAIL_VERIFICATION, tenantA, false, NOW.plusSeconds(60))));
        when(repository.markUsedIfUnused(any(), eq(NOW))).thenReturn(1);

        assertThat(service.consumeCode(code, OneTimeTokenPurpose.EMAIL_VERIFICATION, tenantA, target).success()).isTrue();
    }

    @Test
    void invalidateForTargetDelegatesToRepository() {
        when(repository.invalidateForTarget(OneTimeTokenPurpose.PASSWORD_RESET, tenantA, "a@x.com", NOW)).thenReturn(2);
        assertThat(service.invalidateForTarget(OneTimeTokenPurpose.PASSWORD_RESET, tenantA, "a@x.com")).isEqualTo(2);
    }

    // ---- helpers -----------------------------------------------------------

    private IssueTokenCommand command(OneTimeTokenPurpose purpose, OneTimeTokenFormat format, String target) {
        Duration ttl = purpose == OneTimeTokenPurpose.PASSWORD_RESET ? Duration.ofMinutes(30) : Duration.ofMinutes(15);
        return new IssueTokenCommand(purpose, tenantA, userId, target, null, format, ttl);
    }

    private OneTimeToken token(OneTimeTokenPurpose purpose, UUID tenantId, boolean used, Instant expiresAt) {
        OneTimeToken t = new OneTimeToken();
        t.setId(UUID.randomUUID());
        t.setTokenHash("stored-hash");
        t.setPurpose(purpose);
        t.setTenantId(tenantId);
        t.setUserId(userId);
        t.setTarget("a@x.com");
        t.setUsed(used);
        t.setExpiresAt(expiresAt);
        return t;
    }

    private void stubFound(String rawToken, OneTimeToken token) {
        when(repository.findByTokenHash(generator.hash(rawToken))).thenReturn(Optional.of(token));
    }

    private OneTimeToken captureSaved() {
        ArgumentCaptor<OneTimeToken> captor = ArgumentCaptor.forClass(OneTimeToken.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}

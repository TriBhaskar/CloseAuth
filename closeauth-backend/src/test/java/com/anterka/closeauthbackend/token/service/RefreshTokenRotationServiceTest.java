package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import com.anterka.closeauthbackend.token.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit tests for the replay-detection DECISION logic ({@link RefreshTokenRotationService#authorizeRotation})
 * against a mocked repository — the concurrency primitive (the atomic {@code markUsedIfActive} affected-count) is
 * modelled directly, so scenario #7 (benign concurrent double-use must NOT revoke) is proven deterministically here.
 */
class RefreshTokenRotationServiceTest {

    private RefreshTokenRepository repository;
    private TokenRevocationService tokenRevocationService;
    private RefreshTokenRotationService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(RefreshTokenRepository.class);
        tokenRevocationService = Mockito.mock(TokenRevocationService.class);
        service = new RefreshTokenRotationService(repository, tokenRevocationService);
    }

    private RefreshToken token(RefreshTokenStatus status, UUID familyId, Instant expiresAt) {
        RefreshToken t = new RefreshToken();
        t.setId(UUID.randomUUID());
        t.setTokenHash("hash");
        t.setUserId(UUID.randomUUID());
        t.setTenantId(UUID.randomUUID());
        t.setClientRegisteredId("client-1");
        t.setFamilyId(familyId);
        t.setStatus(status);
        t.setExpiresAt(expiresAt);
        return t;
    }

    private Instant future() {
        return Instant.now().plus(14, ChronoUnit.DAYS);
    }

    @Test
    void activeTokenWinningTheAtomicTransitionProceeds() {
        RefreshToken active = token(RefreshTokenStatus.ACTIVE, UUID.randomUUID(), future());
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(active));
        when(repository.markUsedIfActive(eq(active.getId()), any())).thenReturn(1); // won

        RotationOutcome outcome = service.authorizeRotation("hash");

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.PROCEED);
        assertThat(outcome.parent()).isSameAs(active);
        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    void benignConcurrentDoubleUseLosesTheRaceWithoutRevokingTheFamily() {
        // Both requests read the token as ACTIVE; this one loses the atomic conditional update (affected == 0).
        RefreshToken active = token(RefreshTokenStatus.ACTIVE, UUID.randomUUID(), future());
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(active));
        when(repository.markUsedIfActive(eq(active.getId()), any())).thenReturn(0); // lost the race

        RotationOutcome outcome = service.authorizeRotation("hash");

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.RACE_LOST);
        verify(repository, never()).revokeFamily(any(), any()); // CRITICAL: a benign race must NOT nuke the family
    }

    @Test
    void replayOfUsedTokenRevokesEntireFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken used = token(RefreshTokenStatus.USED, family, future());
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(used));

        RotationOutcome outcome = service.authorizeRotation("hash");

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.REPLAY);
        verify(repository).revokeFamily(eq(family), any());
        verify(repository, never()).markUsedIfActive(any(), any());
        // Loop closure (4b-ii): replay also kills the user's outstanding ACCESS tokens.
        verify(tokenRevocationService).revokeAllUserTokens(used.getTenantId(), used.getUserId());
    }

    @Test
    void replayOfRevokedTokenRevokesFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken revoked = token(RefreshTokenStatus.REVOKED, family, future());
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(revoked));

        RotationOutcome outcome = service.authorizeRotation("hash");

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.REPLAY);
        verify(repository).revokeFamily(eq(family), any());
    }

    @Test
    void expiredTokenFailsWithoutRevocation() {
        RefreshToken expired = token(RefreshTokenStatus.ACTIVE, UUID.randomUUID(),
                Instant.now().minus(1, ChronoUnit.HOURS)); // past expiry
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(expired));

        RotationOutcome outcome = service.authorizeRotation("hash");

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.EXPIRED);
        verify(repository).markExpiredIfActive(expired.getId());
        verify(repository, never()).revokeFamily(any(), any());   // expiry is not a compromise
    }

    @Test
    void unknownTokenFailsWithoutRevocation() {
        when(repository.findByTokenHash("hash")).thenReturn(Optional.empty());

        RotationOutcome outcome = service.authorizeRotation("hash");

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.UNKNOWN);
        verify(repository, never()).revokeFamily(any(), any());
    }
}

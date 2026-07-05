package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import com.anterka.closeauthbackend.token.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The refresh-token rotation + replay-detection ledger (§7.3) — the security-critical core of the token layer,
 * deliberately factored out of the SAS wiring so the algorithm is unit-testable in isolation.
 *
 * <p><b>Rotation (happy path):</b> presenting an ACTIVE token transitions it {@code ACTIVE -> USED} and a fresh
 * ACTIVE child is recorded in the same family ({@code parent_token_id} = the presented token). A legitimate client
 * walks a chain {@code RT1 -> RT2 -> RT3}, each prior token left {@code USED} as a tripwire.
 *
 * <p><b>Replay detection:</b> presenting a token that is already {@code USED} or {@code REVOKED} is a replay — the
 * whole family is compromised, so the ENTIRE family is {@code REVOKED} (including the legitimate tip), forcing
 * re-authentication. Rejecting only the replayed token would leave an attacker's branch alive.
 *
 * <p><b>Concurrency distinction (the crux):</b> {@link #authorizeRotation} reads the token, then does an atomic
 * conditional transition ({@code markUsedIfActive}). A request that read the token as <b>ACTIVE</b> but lost the
 * transition ({@code affected == 0}) is a benign concurrent double-submit — it fails WITHOUT revoking. A request
 * that reads the token as <b>already USED/REVOKED</b> is a replay — it revokes the family. Consequently a replay
 * is NEVER missed (any USED/REVOKED presentation revokes), while the true concurrent case (both requests read
 * ACTIVE; one wins, one loses) does not falsely nuke a legitimate family. The residual case — a benign submit that
 * arrives after the concurrent winner has committed (so it reads USED) — is treated as a replay; this is the
 * deliberate, safe direction (a false re-auth beats a missed replay), per §7.3 / the OAuth 2.0 Security BCP.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenRotationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRevocationService tokenRevocationService;

    /** Records the root of a new refresh-token family (a fresh login's first refresh token), unlinked to a session. */
    @Transactional
    public RefreshToken recordInitialToken(UUID userId, UUID tenantId, String clientRegisteredId,
                                           RefreshTokenIssuance issuance) {
        return recordInitialToken(userId, tenantId, clientRegisteredId, issuance, null);
    }

    /**
     * Records the root of a new refresh-token family, linked to the Auth Server session it belongs to (Stage 6a).
     * The {@code sessionId} makes {@link #revokeSessionFamilies} (Stage 5's session-scoped refresh revocation) work:
     * revoking the session cascades to this family. Rotated children inherit the same {@code session_id}.
     */
    @Transactional
    public RefreshToken recordInitialToken(UUID userId, UUID tenantId, String clientRegisteredId,
                                           RefreshTokenIssuance issuance, UUID sessionId) {
        return persist(userId, tenantId, clientRegisteredId, UUID.randomUUID(), null, issuance, sessionId);
    }

    /** Records a rotated child in the parent's family, inheriting the parent's session linkage (Stage 6a). */
    @Transactional
    public RefreshToken recordRotatedToken(RefreshToken parent, RefreshTokenIssuance issuance) {
        return persist(parent.getUserId(), parent.getTenantId(), parent.getClientRegisteredId(),
                parent.getFamilyId(), parent.getId(), issuance, parent.getSessionId());
    }

    /**
     * Runs replay detection + the atomic transition on a presented refresh token. On {@link RotationOutcome.Type#REPLAY}
     * the family has already been revoked here. The caller (SAS wiring) issues new tokens only on
     * {@link RotationOutcome.Type#PROCEED}, and rejects the refresh request otherwise.
     */
    @Transactional
    public RotationOutcome authorizeRotation(String presentedTokenHash) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(presentedTokenHash).orElse(null);
        if (token == null) {
            return RotationOutcome.unknown();
        }

        Instant now = Instant.now();
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(now)) {
            refreshTokenRepository.markExpiredIfActive(token.getId()); // lazy expiry; not a compromise
            return RotationOutcome.expired();
        }

        return switch (token.getStatus()) {
            case ACTIVE -> {
                int affected = refreshTokenRepository.markUsedIfActive(token.getId(), now);
                // affected==1: this request won the atomic transition -> rotate.
                // affected==0: a concurrent request transitioned it first -> benign race, NOT a replay.
                yield affected == 1 ? RotationOutcome.proceed(token) : RotationOutcome.raceLost();
            }
            case USED, REVOKED -> {
                // Replay: a token consumed by a prior COMPLETED rotation is presented again -> family compromised.
                int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
                // 4b-ii loop closure: a detected refresh-token theft also instantly kills the compromised session's
                // outstanding ACCESS tokens (via the Redis revocation marker), not just the refresh family.
                tokenRevocationService.revokeAllUserTokens(token.getTenantId(), token.getUserId());
                log.warn("REFRESH_TOKEN_REPLAY_DETECTED family={} tenant={} user={} client={} presentedStatus={} "
                                + "familyTokensRevoked={}",
                        token.getFamilyId(), token.getTenantId(), token.getUserId(),
                        token.getClientRegisteredId(), token.getStatus(), revoked);
                // TODO(stage-8): emit a REFRESH_TOKEN_REPLAY_DETECTED audit event via the audit outbox (§7.11).
                yield RotationOutcome.replay();
            }
            case EXPIRED -> RotationOutcome.expired();
        };
    }

    /** Capability for Stage 7 / 4b-ii: revoke an entire refresh-token family. Returns rows revoked. */
    @Transactional
    public int revokeFamily(UUID familyId) {
        return refreshTokenRepository.revokeFamily(familyId, Instant.now());
    }

    /** Capability for Stage 7 / 4b-ii: revoke all of a user's refresh tokens within a tenant. Returns rows revoked. */
    @Transactional
    public int revokeAllUserFamilies(UUID userId, UUID tenantId) {
        return refreshTokenRepository.revokeAllUserFamilies(userId, tenantId, Instant.now());
    }

    /**
     * Stage 5 seam: link a refresh-token family to an Auth Server session (the auth-code flow wires the call in
     * Stage 6), so that revoking the session cascades to its refresh tokens via {@link #revokeSessionFamilies}.
     */
    @Transactional
    public void linkFamilyToSession(UUID familyId, UUID sessionId) {
        refreshTokenRepository.linkFamilyToSession(familyId, sessionId);
    }

    /** Stage 5 revoke cascade: revoke every refresh token belonging to a session (session-scoped). Returns rows revoked. */
    @Transactional
    public int revokeSessionFamilies(UUID sessionId) {
        return refreshTokenRepository.revokeAllSessionFamilies(sessionId, Instant.now());
    }

    private RefreshToken persist(UUID userId, UUID tenantId, String clientRegisteredId, UUID familyId,
                                 UUID parentTokenId, RefreshTokenIssuance issuance, UUID sessionId) {
        RefreshToken token = new RefreshToken();
        token.setTokenHash(issuance.tokenHash());
        token.setUserId(userId);
        token.setTenantId(tenantId);
        token.setClientRegisteredId(clientRegisteredId);
        token.setFamilyId(familyId);
        token.setParentTokenId(parentTokenId);
        token.setStatus(RefreshTokenStatus.ACTIVE);
        token.setScopes(issuance.scopes());
        token.setSessionId(sessionId); // Stage 6a: links the family to its Auth Server session for revoke-cascade
        token.setIpAddress(issuance.ipAddress());
        token.setUserAgent(issuance.userAgent());
        token.setExpiresAt(issuance.expiresAt());
        return refreshTokenRepository.save(token);
    }
}

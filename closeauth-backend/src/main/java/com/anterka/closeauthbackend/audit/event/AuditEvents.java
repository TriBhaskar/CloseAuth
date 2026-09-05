package com.anterka.closeauthbackend.audit.event;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.enums.AuditOutcome;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single place each audit event type's payload shape is defined (§7.11). One static factory per event type;
 * a call site never hand-builds a {@code Map} — it calls {@code AuditEvents.loginSuccess(...)} etc. and gets a
 * fully-typed {@link CloseAuthAuditEvent} with a consistent {@code event_data} schema. This is what makes the
 * query API's {@code event_data} genuinely queryable rather than a per-call-site junk drawer.
 *
 * <p>Actor conventions: methods set an explicit actor ONLY where it is unambiguously the subject and there is no
 * authenticated principal to read (a login records the user who just authenticated; a self-service OTT flow records
 * that user). Admin-mutation events leave the actor UNSET so {@code AuditEmitter} fills it from the calling admin's
 * security context (subject = the target, actor = the admin).
 */
public final class AuditEvents {

    private AuditEvents() {
    }

    // ---- Authentication ----------------------------------------------------

    /** {@code amr} is the RFC 8176 auth method (e.g. {@code magic_link}); {@code idp} the credential source. */
    public static CloseAuthAuditEvent loginSuccess(UUID tenantId, UUID userId, String clientId, String idp, String amr) {
        return base(AuditEventType.USER_LOGIN_SUCCESS)
                .tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("client_id", clientId, "idp", idp, "amr", amr))
                .build();
    }

    /** Enumeration-safe: the specific factor that failed is recorded here (audit-only), never surfaced to the caller. */
    public static CloseAuthAuditEvent loginFailure(UUID tenantId, String reason) {
        return base(AuditEventType.USER_LOGIN_FAILURE).outcome(AuditOutcome.FAILURE).errorCode(reason)
                .tenantId(tenantId).data(data("reason", reason)).build();
    }

    public static CloseAuthAuditEvent logout(UUID tenantId, UUID userId) {
        return base(AuditEventType.USER_LOGOUT).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data()).build();
    }

    public static CloseAuthAuditEvent tokenIssued(UUID tenantId, UUID userId, String clientRegisteredId,
                                                  UUID familyId, UUID sessionId) {
        return base(AuditEventType.TOKEN_ISSUED)
                .tenantId(tenantId).subjectUserId(userId).actorUserId(userId).actorClientRegisteredId(clientRegisteredId)
                .data(data("family_id", str(familyId), "session_id", str(sessionId))).build();
    }

    public static CloseAuthAuditEvent refreshTokenRotated(UUID tenantId, UUID userId, String clientRegisteredId,
                                                          UUID familyId) {
        return base(AuditEventType.REFRESH_TOKEN_ROTATED)
                .tenantId(tenantId).subjectUserId(userId).actorUserId(userId).actorClientRegisteredId(clientRegisteredId)
                .data(data("family_id", str(familyId))).build();
    }

    public static CloseAuthAuditEvent refreshTokenReplayDetected(UUID tenantId, UUID userId, String clientRegisteredId,
                                                                UUID familyId, int familyTokensRevoked) {
        return base(AuditEventType.REFRESH_TOKEN_REPLAY_DETECTED).outcome(AuditOutcome.FAILURE)
                .errorCode("refresh_token.replay")
                .tenantId(tenantId).subjectUserId(userId).actorUserId(userId).actorClientRegisteredId(clientRegisteredId)
                .data(data("family_id", str(familyId), "family_tokens_revoked", familyTokensRevoked)).build();
    }

    /**
     * A refresh rotation refused because the token's TENANT is not ACTIVE (suspended/deleted) — a routine
     * lifecycle consequence, deliberately distinct from {@link #refreshTokenReplayDetected} (a compromise signal).
     * The family is revoked (no legitimate further use), but this must NEVER be read as a stolen-token event.
     */
    public static CloseAuthAuditEvent refreshTokenRejectedTenantInactive(UUID tenantId, UUID userId,
                                                                         String clientRegisteredId, UUID familyId,
                                                                         int familyTokensRevoked) {
        return base(AuditEventType.REFRESH_TOKEN_REJECTED_TENANT_INACTIVE).outcome(AuditOutcome.FAILURE)
                .errorCode("refresh_token.tenant_inactive")
                .tenantId(tenantId).subjectUserId(userId).actorUserId(userId).actorClientRegisteredId(clientRegisteredId)
                .data(data("family_id", str(familyId), "family_tokens_revoked", familyTokensRevoked)).build();
    }

    /** {@code scope} = {@code USER} / {@code TENANT} / {@code PLATFORM_ADMIN} — which revocation marker was written. */
    public static CloseAuthAuditEvent userTokensRevoked(UUID tenantId, UUID userId) {
        return base(AuditEventType.TOKEN_REVOKED).tenantId(tenantId).subjectUserId(userId)
                .data(data("scope", "USER")).build();
    }

    public static CloseAuthAuditEvent tenantTokensRevoked(UUID tenantId) {
        return base(AuditEventType.TOKEN_REVOKED).tenantId(tenantId).data(data("scope", "TENANT")).build();
    }

    public static CloseAuthAuditEvent platformAdminTokensRevoked(UUID platformAdminId) {
        return base(AuditEventType.TOKEN_REVOKED).actorPlatformAdminId(platformAdminId)
                .data(data("scope", "PLATFORM_ADMIN", "platform_admin_id", str(platformAdminId))).build();
    }

    /** Emitted only when introspection finds a REVOKED (but not-yet-expired) token — the security-relevant subset. */
    public static CloseAuthAuditEvent tokenIntrospectedRevoked(UUID tenantId, UUID subjectUserId, String principalType) {
        return base(AuditEventType.TOKEN_INTROSPECTED).outcome(AuditOutcome.FAILURE).errorCode("token.revoked")
                .tenantId(tenantId).subjectUserId(subjectUserId)
                .data(data("result", "revoked", "principal_type", principalType)).build();
    }

    // ---- One-time-token auth flows ----------------------------------------

    public static CloseAuthAuditEvent emailVerificationIssued(UUID tenantId, UUID userId, String target) {
        return base(AuditEventType.EMAIL_VERIFICATION_ISSUED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("target", target)).build();
    }

    public static CloseAuthAuditEvent emailVerified(UUID tenantId, UUID userId) {
        return base(AuditEventType.EMAIL_VERIFIED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data()).build();
    }

    public static CloseAuthAuditEvent magicLinkIssued(UUID tenantId, UUID userId, String target) {
        return base(AuditEventType.MAGIC_LINK_ISSUED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("target", target)).build();
    }

    public static CloseAuthAuditEvent passwordResetRequested(UUID tenantId, UUID userId, String target) {
        return base(AuditEventType.PASSWORD_RESET_REQUESTED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("target", target)).build();
    }

    public static CloseAuthAuditEvent passwordResetCompleted(UUID tenantId, UUID userId, int revokedSessions) {
        return base(AuditEventType.PASSWORD_RESET_COMPLETED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("revoked_sessions", revokedSessions)).build();
    }

    /** Security-relevant OTT consume failure (replay / expired / wrong-tenant / wrong-purpose). */
    public static CloseAuthAuditEvent oneTimeTokenConsumeFailed(UUID tenantId, String purpose, String reason) {
        return base(AuditEventType.ONE_TIME_TOKEN_CONSUME_FAILED).outcome(AuditOutcome.FAILURE).errorCode(reason)
                .tenantId(tenantId).data(data("purpose", purpose, "reason", reason)).build();
    }

    public static CloseAuthAuditEvent inviteIssued(UUID tenantId, String email, UUID tokenId) {
        return base(AuditEventType.INVITE_ISSUED).tenantId(tenantId)
                .data(data("email", email, "token_id", str(tokenId))).build();
    }

    public static CloseAuthAuditEvent inviteRevoked(UUID tenantId, UUID inviteId) {
        return base(AuditEventType.INVITE_REVOKED).tenantId(tenantId).data(data("invite_id", str(inviteId))).build();
    }

    /**
     * A system-generated temporary credential was issued for a tenant's first admin (Phase 3 of the
     * tenant-onboarding design, {@code TenantOnboardingService.bootstrapFirstAdmin}). Actor deliberately UNSET —
     * the calling platform admin is filled in from the security context (admin-mutation convention).
     */
    public static CloseAuthAuditEvent tempCredentialIssued(UUID tenantId, UUID userId, Instant expiresAt) {
        return base(AuditEventType.TEMP_CREDENTIAL_ISSUED).tenantId(tenantId).subjectUserId(userId)
                .data(data("expires_at", str(expiresAt))).build();
    }

    /** As {@link #tempCredentialIssued}, but for {@code TenantOnboardingService.reissueOnboardingCredential}. */
    public static CloseAuthAuditEvent tempCredentialReissued(UUID tenantId, UUID userId, Instant expiresAt) {
        return base(AuditEventType.TEMP_CREDENTIAL_REISSUED).tenantId(tenantId).subjectUserId(userId)
                .data(data("expires_at", str(expiresAt))).build();
    }

    // ---- Session -----------------------------------------------------------

    public static CloseAuthAuditEvent sessionCreated(UUID tenantId, UUID userId, UUID sessionId, boolean rememberMe) {
        return base(AuditEventType.SESSION_CREATED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("session_id", str(sessionId), "remember_me", rememberMe)).build();
    }

    /** {@code sessionId} null for a revoke-all; {@code reason} e.g. {@code logout}/{@code revoke_all}/{@code password_reset}. */
    public static CloseAuthAuditEvent sessionRevoked(UUID tenantId, UUID userId, UUID sessionId, String reason) {
        return base(AuditEventType.SESSION_REVOKED).tenantId(tenantId).subjectUserId(userId)
                .data(data("session_id", str(sessionId), "reason", reason)).build();
    }

    // ---- Identity ----------------------------------------------------------

    /** {@code createdBy} = {@code ADMIN} / {@code SELF_REGISTRATION}. */
    public static CloseAuthAuditEvent userCreated(UUID tenantId, UUID userId, String createdBy) {
        return base(AuditEventType.USER_CREATED).tenantId(tenantId).subjectUserId(userId)
                .data(data("created_by", createdBy)).build();
    }

    public static CloseAuthAuditEvent userSuspended(UUID tenantId, UUID userId) {
        return base(AuditEventType.USER_SUSPENDED).tenantId(tenantId).subjectUserId(userId).data(data()).build();
    }

    public static CloseAuthAuditEvent userActivated(UUID tenantId, UUID userId) {
        return base(AuditEventType.USER_ACTIVATED).tenantId(tenantId).subjectUserId(userId).data(data()).build();
    }

    public static CloseAuthAuditEvent userDeleted(UUID tenantId, UUID userId) {
        return base(AuditEventType.USER_DELETED).tenantId(tenantId).subjectUserId(userId).data(data()).build();
    }

    /** {@code reason} = {@code PASSWORD_RESET} / {@code ADMIN_RESET} / {@code SELF_CHANGE}. */
    public static CloseAuthAuditEvent passwordChanged(UUID tenantId, UUID userId, String reason) {
        return base(AuditEventType.PASSWORD_CHANGED).tenantId(tenantId).subjectUserId(userId).actorUserId(userId)
                .data(data("reason", reason)).build();
    }

    // ---- Tenant ------------------------------------------------------------

    public static CloseAuthAuditEvent tenantCreated(UUID tenantId, String slug, String name) {
        return base(AuditEventType.TENANT_CREATED).tenantId(tenantId).data(data("slug", slug, "name", name)).build();
    }

    public static CloseAuthAuditEvent tenantActivated(UUID tenantId) {
        return base(AuditEventType.TENANT_ACTIVATED).tenantId(tenantId).data(data()).build();
    }

    public static CloseAuthAuditEvent tenantSuspended(UUID tenantId) {
        return base(AuditEventType.TENANT_SUSPENDED).tenantId(tenantId).data(data()).build();
    }

    public static CloseAuthAuditEvent tenantDeleted(UUID tenantId) {
        return base(AuditEventType.TENANT_DELETED).tenantId(tenantId).data(data()).build();
    }

    public static CloseAuthAuditEvent tenantBrandingChanged(UUID tenantId) {
        return base(AuditEventType.TENANT_BRANDING_CHANGED).tenantId(tenantId).data(data()).build();
    }

    public static CloseAuthAuditEvent tenantRegistrationPolicyChanged(UUID tenantId, String mode) {
        return base(AuditEventType.TENANT_REGISTRATION_POLICY_CHANGED).tenantId(tenantId)
                .data(data("mode", mode)).build();
    }

    // ---- Authorization -----------------------------------------------------

    /** {@code roleType} = {@code TENANT} / {@code APPLICATION}. */
    public static CloseAuthAuditEvent roleAssigned(UUID tenantId, UUID subjectUserId, String roleType, UUID roleId,
                                                   UUID assignedByUserId) {
        return base(AuditEventType.ROLE_ASSIGNED).tenantId(tenantId).subjectUserId(subjectUserId)
                .actorUserId(assignedByUserId)
                .data(data("role_type", roleType, "role_id", str(roleId))).build();
    }

    public static CloseAuthAuditEvent roleRevoked(UUID tenantId, UUID subjectUserId, String roleType, UUID roleId) {
        return base(AuditEventType.ROLE_REVOKED).tenantId(tenantId).subjectUserId(subjectUserId)
                .data(data("role_type", roleType, "role_id", str(roleId))).build();
    }

    public static CloseAuthAuditEvent consentGranted(UUID tenantId, String principalName, String clientRegisteredId,
                                                     List<String> scopes) {
        return base(AuditEventType.CONSENT_GRANTED).tenantId(tenantId).actorClientRegisteredId(clientRegisteredId)
                .data(data("principal_name", principalName, "client_registered_id", clientRegisteredId,
                        "scopes", scopes)).build();
    }

    public static CloseAuthAuditEvent consentRevoked(UUID tenantId, String principalName, String clientRegisteredId) {
        return base(AuditEventType.CONSENT_REVOKED).tenantId(tenantId).actorClientRegisteredId(clientRegisteredId)
                .data(data("principal_name", principalName, "client_registered_id", clientRegisteredId)).build();
    }

    // ---- Client / Resource Server -----------------------------------------

    public static CloseAuthAuditEvent clientRegistered(UUID tenantId, String clientRegisteredId, String clientId) {
        return base(AuditEventType.CLIENT_REGISTERED).tenantId(tenantId).actorClientRegisteredId(clientRegisteredId)
                .data(data("client_registered_id", clientRegisteredId, "client_id", clientId)).build();
    }

    /** UI-3c: a confidential client's secret was rotated. Deliberately its own event, not folded into {@code CLIENT_UPDATED}. */
    public static CloseAuthAuditEvent clientSecretRegenerated(UUID tenantId, String clientRegisteredId, String clientId) {
        return base(AuditEventType.CLIENT_SECRET_REGENERATED).tenantId(tenantId).actorClientRegisteredId(clientRegisteredId)
                .data(data("client_registered_id", clientRegisteredId, "client_id", clientId)).build();
    }

    /** Client update/delete: a client's mutable fields (name/scopes/redirect URIs/PKCE/trusted) were replaced. */
    public static CloseAuthAuditEvent clientUpdated(UUID tenantId, String clientRegisteredId, String clientId) {
        return base(AuditEventType.CLIENT_UPDATED).tenantId(tenantId).actorClientRegisteredId(clientRegisteredId)
                .data(data("client_registered_id", clientRegisteredId, "client_id", clientId)).build();
    }

    /**
     * Client update/delete: a client (and its 1:1 auto-created resource server) was hard-deleted. Recorded
     * BEFORE the row is gone (this event is what makes {@code actor_client_id} on an already-deleted client
     * legal — see {@code V3__relax_audit_actor_fks.sql}, which relaxed that FK to RESTRICT-free for exactly
     * this reason).
     */
    public static CloseAuthAuditEvent clientDeleted(UUID tenantId, String clientRegisteredId, String clientId) {
        return base(AuditEventType.CLIENT_DELETED).tenantId(tenantId).actorClientRegisteredId(clientRegisteredId)
                .data(data("client_registered_id", clientRegisteredId, "client_id", clientId)).build();
    }

    public static CloseAuthAuditEvent resourceServerCreated(UUID tenantId, UUID resourceServerId, String name) {
        return base(AuditEventType.RESOURCE_SERVER_CREATED).tenantId(tenantId).resourceServerId(resourceServerId)
                .data(data("name", name)).build();
    }

    public static CloseAuthAuditEvent scopeDefined(UUID tenantId, UUID resourceServerId, String scopeName) {
        return base(AuditEventType.SCOPE_DEFINED).tenantId(tenantId).resourceServerId(resourceServerId)
                .data(data("scope", scopeName)).build();
    }

    public static CloseAuthAuditEvent scopeRemoved(UUID tenantId, UUID resourceServerId, UUID scopeId) {
        return base(AuditEventType.SCOPE_REMOVED).tenantId(tenantId).resourceServerId(resourceServerId)
                .data(data("scope_id", str(scopeId))).build();
    }

    // ---- Administrative ----------------------------------------------------

    public static CloseAuthAuditEvent platformAdminLoginSuccess(UUID platformAdminId) {
        return base(AuditEventType.ADMIN_LOGIN).actorPlatformAdminId(platformAdminId).data(data()).build();
    }

    public static CloseAuthAuditEvent platformAdminLoginFailure(String reason) {
        return base(AuditEventType.ADMIN_LOGIN).outcome(AuditOutcome.FAILURE).errorCode(reason)
                .data(data("reason", reason)).build();
    }

    /** Platform-level configuration change (§7.11 {@code PLATFORM_CONFIGURATION_CHANGED}) — e.g. platform-admin CRUD. */
    public static CloseAuthAuditEvent platformConfigurationChanged(String action, UUID targetPlatformAdminId) {
        return base(AuditEventType.PLATFORM_CONFIGURATION_CHANGED)
                .data(data("action", action, "target_platform_admin_id", str(targetPlatformAdminId))).build();
    }

    // ---- helpers -----------------------------------------------------------

    private static CloseAuthAuditEvent.CloseAuthAuditEventBuilder base(AuditEventType type) {
        return CloseAuthAuditEvent.builder().eventType(type);
    }

    private static String str(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String str(Instant value) {
        return value == null ? null : value.toString();
    }

    /** Ordered payload map from alternating key/value pairs; null values are skipped (JSONB stays lean). */
    private static Map<String, Object> data(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("keyValuePairs must be even-length");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                map.put((String) keyValuePairs[i], value);
            }
        }
        return map;
    }
}

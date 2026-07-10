package com.anterka.closeauthbackend.audit.event;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.enums.AuditOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Representative event-correctness (§7.11 "Testing"): pick several event types across the taxonomy and assert the
 * factory emits the right shape — event type, outcome, subject/actor attribution, and the typed {@code event_data}
 * keys. This is what keeps {@code event_data} a queryable schema rather than a per-call-site junk drawer.
 */
class AuditEventsTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void loginSuccessAttributesUserAsBothSubjectAndActor() {
        CloseAuthAuditEvent e = AuditEvents.loginSuccess(tenant, user, "web-client", "LOCAL_PASSWORD", "magic_link");
        assertThat(e.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_SUCCESS);
        assertThat(e.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(e.getSubjectUserId()).isEqualTo(user);
        assertThat(e.getActorUserId()).isEqualTo(user);
        assertThat(e.getData()).containsEntry("amr", "magic_link").containsEntry("idp", "LOCAL_PASSWORD");
    }

    @Test
    void loginFailureIsFailureOutcomeWithReasonAndNoActor() {
        CloseAuthAuditEvent e = AuditEvents.loginFailure(tenant, "INVALID_CREDENTIALS");
        assertThat(e.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_FAILURE);
        assertThat(e.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(e.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(e.getData()).containsEntry("reason", "INVALID_CREDENTIALS");
        assertThat(e.hasNoActor()).isTrue(); // enrichment fills nothing for a pre-auth failure
    }

    @Test
    void tenantSuspendedCarriesTenantOnly() {
        CloseAuthAuditEvent e = AuditEvents.tenantSuspended(tenant);
        assertThat(e.getEventType()).isEqualTo(AuditEventType.TENANT_SUSPENDED);
        assertThat(e.getTenantId()).isEqualTo(tenant);
        assertThat(e.getSubjectUserId()).isNull();
        assertThat(e.hasNoActor()).isTrue(); // actor (the admin) is filled by enrichment, not the factory
    }

    @Test
    void roleRevokedRecordsSubjectAndTypedRoleData() {
        UUID roleId = UUID.randomUUID();
        CloseAuthAuditEvent e = AuditEvents.roleRevoked(tenant, user, "TENANT", roleId);
        assertThat(e.getEventType()).isEqualTo(AuditEventType.ROLE_REVOKED);
        assertThat(e.getSubjectUserId()).isEqualTo(user);
        assertThat(e.getData()).containsEntry("role_type", "TENANT").containsEntry("role_id", roleId.toString());
    }

    @Test
    void refreshTokenReplayIsSecurityFailureWithFamilyContext() {
        UUID family = UUID.randomUUID();
        CloseAuthAuditEvent e = AuditEvents.refreshTokenReplayDetected(tenant, user, "cid-123", family, 3);
        assertThat(e.getEventType()).isEqualTo(AuditEventType.REFRESH_TOKEN_REPLAY_DETECTED);
        assertThat(e.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(e.getActorClientRegisteredId()).isEqualTo("cid-123");
        assertThat(e.getData()).containsEntry("family_id", family.toString()).containsEntry("family_tokens_revoked", 3);
    }

    @Test
    void consentGrantedCarriesScopesList() {
        CloseAuthAuditEvent e = AuditEvents.consentGranted(tenant, "user@x.io", "cid-9", List.of("read", "write"));
        assertThat(e.getEventType()).isEqualTo(AuditEventType.CONSENT_GRANTED);
        assertThat(e.getData()).containsEntry("scopes", List.of("read", "write"));
    }

    @Test
    void nullValuesAreOmittedFromPayload() {
        CloseAuthAuditEvent e = AuditEvents.userCreated(tenant, user, null); // createdBy null → key omitted
        assertThat(e.getData()).doesNotContainKey("created_by");
    }
}

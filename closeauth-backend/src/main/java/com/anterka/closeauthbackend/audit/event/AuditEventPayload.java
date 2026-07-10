package com.anterka.closeauthbackend.audit.event;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.enums.AuditOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * The wire shape of an audit event as stored in {@code audit_outbox.event_payload} (JSONB). It maps 1:1 to the
 * {@code audit_events} columns plus the typed {@code data} payload, so the drain worker can reconstruct the full
 * {@code audit_events} row from an outbox row alone — the outbox row is self-contained (the business action already
 * committed; the worker needs nothing else). Serialized/deserialized by Jackson.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventPayload(
        AuditEventType eventType,
        AuditOutcome outcome,
        UUID tenantId,
        UUID subjectUserId,
        UUID actorUserId,
        UUID actorPlatformAdminId,
        String actorClientRegisteredId,
        UUID actorAgentId,
        UUID grantedConsentId,
        UUID resourceServerId,
        String ipAddress,
        String userAgent,
        String errorCode,
        Map<String, Object> data) {

    public static AuditEventPayload from(CloseAuthAuditEvent event) {
        return new AuditEventPayload(
                event.getEventType(),
                event.getOutcome(),
                event.getTenantId(),
                event.getSubjectUserId(),
                event.getActorUserId(),
                event.getActorPlatformAdminId(),
                event.getActorClientRegisteredId(),
                event.getActorAgentId(),
                event.getGrantedConsentId(),
                event.getResourceServerId(),
                event.getIpAddress(),
                event.getUserAgent(),
                event.getErrorCode(),
                event.getData() == null ? Map.of() : event.getData());
    }
}

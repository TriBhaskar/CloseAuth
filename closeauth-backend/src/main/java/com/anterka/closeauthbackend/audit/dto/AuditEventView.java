package com.anterka.closeauthbackend.audit.dto;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;
import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.enums.AuditOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.UUID;

/**
 * The customer/platform-facing projection of an {@link AuditEvent} (§7.11 query API). Never leaks the entity. The
 * {@code eventData} is emitted as raw JSON ({@link JsonRawValue}) so a caller gets the typed payload object inline,
 * not a string-escaped blob — {@code audit_events.event_data} is always well-formed JSON (the drain worker writes it).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventView(
        UUID id,
        AuditEventType eventType,
        AuditOutcome outcome,
        UUID tenantId,
        UUID subjectUserId,
        UUID actorUserId,
        UUID actorPlatformAdminId,
        String actorClientRegisteredId,
        UUID resourceServerId,
        String ipAddress,
        String userAgent,
        String errorCode,
        @JsonRawValue String eventData,
        Instant createdAt) {

    public static AuditEventView from(AuditEvent e) {
        return new AuditEventView(
                e.getId(),
                e.getEventType(),
                e.getOutcome(),
                e.getTenantId(),
                e.getSubjectUserId(),
                e.getActorUserId(),
                e.getActorPlatformAdminId(),
                e.getActorClientRegisteredId(),
                e.getResourceServerId(),
                e.getIpAddress(),
                e.getUserAgent(),
                e.getErrorCode(),
                e.getEventData() == null ? "{}" : e.getEventData(),
                e.getCreatedAt());
    }
}

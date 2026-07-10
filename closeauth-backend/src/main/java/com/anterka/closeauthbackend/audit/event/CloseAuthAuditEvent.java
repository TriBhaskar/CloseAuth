package com.anterka.closeauthbackend.audit.event;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.enums.AuditOutcome;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.UUID;

/**
 * The in-process audit signal (§7.11) — the "scaffold referenced throughout prior stages", now real. Service code
 * builds one of these and hands it to {@code AuditEmitter}, which publishes it; a {@code @TransactionalEventListener}
 * writes it to {@code audit_outbox} inside the SAME transaction as the business mutation (atomic — see
 * {@code AuditOutboxWriter}). It is deliberately NOT the JPA entity: it carries the fully-resolved shape of an
 * {@code audit_events} row plus request metadata, decoupled from persistence.
 *
 * <p><b>Typed payloads, not a junk drawer:</b> the {@link #data} map is never hand-built at a call site. It is
 * produced by a factory method in {@link AuditEvents} per event type, so every {@code USER_LOGIN_SUCCESS} (etc.)
 * carries the same keys — which is what makes the query API's {@code event_data} genuinely queryable.
 *
 * <p><b>Enrichment:</b> {@link #ipAddress}/{@link #userAgent} and the actor identity are usually left unset at the
 * call site and filled by {@code AuditEmitter} from the current request + security context (an admin mutation's actor
 * is the calling admin). A call site MAY set them explicitly when it knows better (e.g. a login records the user who
 * just authenticated, before any principal exists).
 */
@Getter
@Builder(toBuilder = true)
public class CloseAuthAuditEvent {

    /** The canonical event type (required). */
    private final AuditEventType eventType;

    /** Outcome (required). Failures/security-relevant rejections use {@code FAILURE}; unexpected errors {@code ERROR}. */
    @Builder.Default
    private final AuditOutcome outcome = AuditOutcome.SUCCESS;

    /** Owning tenant; null for platform-level events (e.g. platform-admin login) that predate/transcend any tenant. */
    private final UUID tenantId;

    /** On whose behalf the action occurred (the target user). Equals {@link #actorUserId} for direct user actions. */
    private final UUID subjectUserId;

    /** Tenant-user actor. */
    private final UUID actorUserId;

    /** Platform-admin actor (a distinct principal type from {@code users}; see the V7 column). */
    private final UUID actorPlatformAdminId;

    /** M2M actor — the SAS {@code oauth2_registered_client(id)} PK. */
    private final String actorClientRegisteredId;

    /** Agent actor (Phase 4 — always null in Phase 1). */
    private final UUID actorAgentId;

    /** The consent grant that authorized an agent action (Phase 4 — always null in Phase 1). */
    private final UUID grantedConsentId;

    /** Optional resource-server context. */
    private final UUID resourceServerId;

    /** Set when {@link #outcome} is not {@code SUCCESS} — a stable, machine-readable reason code. */
    private final String errorCode;

    /** Request IP; filled from the current request by the emitter when unset. */
    private final String ipAddress;

    /** Request User-Agent; filled from the current request by the emitter when unset. */
    private final String userAgent;

    /** The typed event payload → {@code audit_events.event_data}. Never null (defaults to empty). */
    @Builder.Default
    private final Map<String, Object> data = Map.of();

    /** True iff no actor field is set — the signal for the emitter to resolve the actor from the security context. */
    public boolean hasNoActor() {
        return actorUserId == null && actorPlatformAdminId == null && actorClientRegisteredId == null
                && actorAgentId == null;
    }
}

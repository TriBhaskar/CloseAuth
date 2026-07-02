package com.anterka.closeauthbackend.audit.entity;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.enums.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A canonical, append-only audit event (Section 7.11). Aggregate root; all principal
 * references are plain UUIDs (or a plain String for the SAS client PK). The delegation
 * chain fields ({@code subjectUserId}/{@code actorUserId}/{@code actorAgentId}/
 * {@code grantedConsentId}) are reserved from Phase 1. Maps {@code audit_events}.
 *
 * <p>No {@code updated_at}: audit rows are immutable once written.
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Mapped as the {@link AuditEventType} enum — the application-layer taxonomy. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 100)
    private AuditEventType eventType;

    /** Nullable for platform-level events that predate any tenant. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** On whose behalf the action was performed. */
    @Column(name = "subject_user_id")
    private UUID subjectUserId;

    /** Who actually performed the action (equals subject for direct actions). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /** M2M actor — references the SAS {@code oauth2_registered_client(id)} PK (plain String). */
    @Column(name = "actor_client_id", length = 100)
    private String actorClientRegisteredId;

    /** Agent actor (Phase 4). */
    @Column(name = "actor_agent_id")
    private UUID actorAgentId;

    /** The consent grant that authorized an agent action (Phase 4). */
    @Column(name = "granted_consent_id")
    private UUID grantedConsentId;

    @Column(name = "resource_server_id")
    private UUID resourceServerId;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    /** Typed payload per event type; enforced at the application layer. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", nullable = false)
    private String eventData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditEvent other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

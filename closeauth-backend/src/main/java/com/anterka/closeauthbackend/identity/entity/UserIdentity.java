package com.anterka.closeauthbackend.identity.entity;

import com.anterka.closeauthbackend.identity.enums.IdpType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A credential source linking a {@link User} to an identity provider (Section 7.4).
 * Within the {@link User} aggregate, so the back-reference to the user is a JPA
 * {@code @ManyToOne} (Rule 1/3). {@code tenantId} and {@code idpConnectionId} are
 * plain UUIDs (cross-aggregate / Rule 2). Maps {@code user_identities}.
 */
@Entity
@Table(name = "user_identities")
@Getter
@Setter
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Within-aggregate back-reference to the owning user. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Cross-aggregate reference — plain UUID (Rule 2), denormalized for isolation checks. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "idp_type", nullable = false, length = 50)
    private IdpType idpType;

    @Column(name = "idp_subject", length = 500)
    private String idpSubject;

    /** Cross-aggregate reference to a tenant_idp_connections row — plain UUID. */
    @Column(name = "idp_connection_id")
    private UUID idpConnectionId;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "password_algo", length = 50)
    private String passwordAlgo;

    /**
     * Forced-rotation gate for a system-generated temp credential (§2.2 of the tenant-onboarding design). Set by
     * {@code UserService.issueTempCredential} (Phase 3 issuance — bootstrap/reissue), read by
     * {@code LoginPolicyService}'s shared credential-lifecycle gate, cleared by
     * {@code UserService.completeForcedRotation} on a successful rotation.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** Hard expiry of a system-generated temp credential (§2.3). Null for user-chosen passwords. */
    @Column(name = "temp_credential_expires_at")
    private Instant tempCredentialExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserIdentity other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

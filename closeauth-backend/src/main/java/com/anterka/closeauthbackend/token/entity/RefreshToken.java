package com.anterka.closeauthbackend.token.entity;

import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
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
 * A server-side refresh token supporting rotation and replay detection (Section 7.3).
 * Aggregate root. Maps {@code refresh_tokens}.
 *
 * <p>{@code parentTokenId} is a plain UUID self-reference, NOT a JPA self-relationship
 * (Rule 3): family revocation is a query over {@code familyId}, never an object-graph
 * lineage walk.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Hash of the opaque token; the plaintext is never stored. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * References the SAS {@code oauth2_registered_client(id)} PK ({@code VARCHAR(100)}),
     * hence a plain String (Rule 5).
     */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientRegisteredId;

    /** All tokens rotated from one original share this; replay revokes the family. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** Plain UUID self-reference (Rule 3) — the token this one was rotated from. */
    @Column(name = "parent_token_id")
    private UUID parentTokenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefreshTokenStatus status;

    @Column
    private String scopes;

    /** Cross-aggregate reference to an auth_server_sessions row — plain UUID. */
    @Column(name = "session_id")
    private UUID sessionId;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = RefreshTokenStatus.ACTIVE;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

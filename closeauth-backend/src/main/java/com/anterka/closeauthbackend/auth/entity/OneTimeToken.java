package com.anterka.closeauthbackend.auth.entity;

import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * A single-use out-of-band secret (§13.4) — the persisted half of the {@code OneTimeTokenService} primitive. Aggregate
 * root. Maps {@code one_time_tokens}.
 *
 * <p><b>Only the hash is stored</b> ({@code token_hash} = SHA-256 of the raw secret); the raw value exists only in
 * transit and in the delivered email. Single-use is enforced by an atomic conditional UPDATE on consume (not by this
 * entity). {@code tenantId}/{@code userId} are plain UUID cross-aggregate references (Rule 2).
 */
@Entity
@Table(name = "one_time_tokens")
@Getter
@Setter
public class OneTimeToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** SHA-256 hex of the raw secret; the plaintext is never stored. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OneTimeTokenPurpose purpose;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Nullable: an INVITE can precede the user row. */
    @Column(name = "user_id")
    private UUID userId;

    /** The email the secret was delivered to. */
    @Column(nullable = false, length = 255)
    private String target;

    /** Small purpose-specific payload (nullable), stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String payload;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used;

    @Column(name = "used_at")
    private Instant usedAt;

    /** Reserved for per-token attempt tracking; the active brute-force control is the flow's per-target limiter. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

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
        if (!(o instanceof OneTimeToken other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

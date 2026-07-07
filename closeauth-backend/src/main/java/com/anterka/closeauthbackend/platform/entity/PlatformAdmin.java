package com.anterka.closeauthbackend.platform.entity;

import com.anterka.closeauthbackend.platform.enums.PlatformAdminStatus;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A CloseAuth platform administrator — staff who operate across all tenants (§7.8). Aggregate root. A SEPARATE entity
 * from tenant {@code users} (keeps {@code users.tenant_id NOT NULL} — the isolation invariant — and makes "a platform
 * admin can never be confused for a tenant user" a type guarantee). Globally-unique email (not tenant-scoped).
 * Credentials are stored inline (staff are password-only in MVP; hashed via the same {@code PasswordHasher} as tenant
 * users). Maps {@code platform_admins}.
 */
@Entity
@Table(name = "platform_admins")
@Getter
@Setter
public class PlatformAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformAdminStatus status;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /** {@code {id}}-prefixed encoded hash (DelegatingPasswordEncoder); never the raw password. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_algo", nullable = false, length = 50)
    private String passwordAlgo;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = PlatformAdminStatus.ACTIVE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlatformAdmin other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

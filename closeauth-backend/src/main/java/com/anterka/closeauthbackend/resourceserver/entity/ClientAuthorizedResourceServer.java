package com.anterka.closeauthbackend.resourceserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Join row recording which Resource Server a Client may request tokens for
 * (Section 7.6). Own entity with plain FK fields. Maps
 * {@code client_authorized_resource_servers}.
 */
@Entity
@Table(name = "client_authorized_resource_servers")
@Getter
@Setter
public class ClientAuthorizedResourceServer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * References the Spring Authorization Server {@code oauth2_registered_client(id)}
     * primary key, which is a {@code VARCHAR(100)} (not a UUID) — hence a plain String,
     * not a UUID. The SAS client table is not JPA-managed in Stage 2 (Rule 5).
     */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientRegisteredId;

    /** Cross-aggregate reference — plain UUID. */
    @Column(name = "resource_server_id", nullable = false)
    private UUID resourceServerId;

    /** Comma-delimited scope names the client can request; NULL means "all scopes". */
    @Column(name = "authorized_scopes")
    private String authorizedScopes;

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
        if (!(o instanceof ClientAuthorizedResourceServer other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

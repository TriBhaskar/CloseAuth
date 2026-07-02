package com.anterka.closeauthbackend.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One scope in an {@link ApplicationRole}'s bundle (Section 7.9). Mapped as a
 * within-aggregate child of ApplicationRole (Rule 3): the back-reference to the role
 * is a JPA {@code @ManyToOne}, while the referenced scope lives in the ResourceServer
 * aggregate and is therefore a plain {@code resourceServerScopeId} UUID. Maps
 * {@code application_role_scopes}.
 */
@Entity
@Table(name = "application_role_scopes")
@Getter
@Setter
public class ApplicationRoleScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Within-aggregate back-reference to the owning application role. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_role_id", nullable = false)
    private ApplicationRole applicationRole;

    /** Cross-aggregate reference to a resource_server_scopes row — plain UUID. */
    @Column(name = "resource_server_scope_id", nullable = false)
    private UUID resourceServerScopeId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApplicationRoleScope other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

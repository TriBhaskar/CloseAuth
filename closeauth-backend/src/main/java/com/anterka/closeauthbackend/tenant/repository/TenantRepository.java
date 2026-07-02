package com.anterka.closeauthbackend.tenant.repository;

import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link Tenant} aggregate root.
 *
 * <p>Tenant is the tenant boundary itself, so its lookups are legitimately global —
 * {@code slug} is globally unique and is the human-facing tenant identifier used for
 * resolution.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /** Global by design: {@code slug} is globally unique (tenant resolution). */
    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Platform-level filtering across tenants (e.g. list ACTIVE tenants). */
    List<Tenant> findByStatus(TenantStatus status);
}

package com.anterka.closeauthbackend.tenant.repository;

import com.anterka.closeauthbackend.tenant.dto.EntryResolutionView;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Projects just the tenant's status by id — a cheap lookup for hot-path status gates (session validation, refresh
     * rotation) that must not load the whole aggregate. Empty if the tenant does not exist.
     */
    @Query("SELECT t.status FROM Tenant t WHERE t.id = :id")
    Optional<TenantStatus> findStatusById(@Param("id") UUID id);

    /**
     * FE-2a: the same "cheap projection, don't load the whole aggregate" discipline as
     * {@link #findStatusById}, for the public workspace-entry resolver — {@code slug}-keyed
     * since that's all an unauthenticated caller ever has, and carrying just enough
     * ({@code slug}/{@code name}/{@code status}) to build {@code EntryResolutionView} without
     * ever loading the internal {@code id} or timestamps.
     */
    @Query("SELECT new com.anterka.closeauthbackend.tenant.dto.EntryResolutionView(t.slug, t.name, t.status) "
            + "FROM Tenant t WHERE t.slug = :slug")
    Optional<EntryResolutionView> findEntryResolutionBySlug(@Param("slug") String slug);
}

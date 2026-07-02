package com.anterka.closeauthbackend.resourceserver.repository;

import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link ResourceServer} aggregate root (its {@code scopes} are
 * reached through it).
 */
@Repository
public interface ResourceServerRepository extends JpaRepository<ResourceServer, UUID> {

    /**
     * Global by design: {@code audience_identifier} is globally unique so the token
     * {@code aud} claim is unambiguous. Used to resolve an incoming {@code aud} to its RS.
     */
    Optional<ResourceServer> findByAudienceIdentifier(String audienceIdentifier);

    @Query("SELECT r FROM ResourceServer r WHERE r.id = :id AND r.tenantId = :tenantId")
    Optional<ResourceServer> findByIdInTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT r FROM ResourceServer r WHERE r.slug = :slug AND r.tenantId = :tenantId")
    Optional<ResourceServer> findBySlugInTenant(@Param("slug") String slug, @Param("tenantId") UUID tenantId);

    List<ResourceServer> findByTenantId(UUID tenantId);
}

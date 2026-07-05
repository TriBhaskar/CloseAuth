package com.anterka.closeauthbackend.tenant.repository;

import com.anterka.closeauthbackend.tenant.entity.TenantBranding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Repository for the {@link TenantBranding} aggregate root (1:1 with tenant). */
@Repository
public interface TenantBrandingRepository extends JpaRepository<TenantBranding, UUID> {

    Optional<TenantBranding> findByTenantId(UUID tenantId);
}

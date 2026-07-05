package com.anterka.closeauthbackend.tenant.repository;

import com.anterka.closeauthbackend.tenant.entity.TenantRegistrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Repository for the {@link TenantRegistrationConfig} aggregate root (1:1 with tenant). */
@Repository
public interface TenantRegistrationConfigRepository extends JpaRepository<TenantRegistrationConfig, UUID> {

    Optional<TenantRegistrationConfig> findByTenantId(UUID tenantId);
}

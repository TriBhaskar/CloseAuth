package com.anterka.closeauthbackend.identity.repository;

import com.anterka.closeauthbackend.identity.entity.TenantIdpConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link TenantIdpConnection} (standalone aggregate root). Empty in
 * MVP; the federation feature that uses it lands in Phase 2. All lookups are
 * tenant-scoped.
 */
@Repository
public interface TenantIdpConnectionRepository extends JpaRepository<TenantIdpConnection, UUID> {

    List<TenantIdpConnection> findByTenantId(UUID tenantId);
}

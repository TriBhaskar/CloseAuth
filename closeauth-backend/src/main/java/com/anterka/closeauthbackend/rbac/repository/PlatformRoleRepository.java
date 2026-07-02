package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.PlatformRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PlatformRole}. Platform roles are not tenant-owned (they
 * apply to CloseAuth-the-platform), so {@code name} is globally unique.
 */
@Repository
public interface PlatformRoleRepository extends JpaRepository<PlatformRole, UUID> {

    Optional<PlatformRole> findByName(String name);
}

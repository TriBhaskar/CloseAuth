package com.anterka.closeauthbackend.platform.repository;

import com.anterka.closeauthbackend.platform.entity.PlatformAdminRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository for {@link PlatformAdminRole} (platform-admin ↔ platform-role assignments). */
@Repository
public interface PlatformAdminRoleRepository extends JpaRepository<PlatformAdminRole, UUID> {

    List<PlatformAdminRole> findByPlatformAdminId(UUID platformAdminId);

    boolean existsByPlatformAdminIdAndPlatformRoleId(UUID platformAdminId, UUID platformRoleId);

    Optional<PlatformAdminRole> findByPlatformAdminIdAndPlatformRoleId(UUID platformAdminId, UUID platformRoleId);
}

package com.anterka.closeauthbackend.platform.repository;

import com.anterka.closeauthbackend.platform.entity.PlatformAdminRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Number of holders of the given platform role whose admin is {@code ACTIVE}. Backs the last-platform-admin
     * invariant (IT-9 fix) with the SAME active-only counting discipline as the tenant tier: a SUSPENDED holder's
     * dormant assignment must NOT count toward "the platform still has an admin" (they can't authenticate).
     */
    @Query(value = """
            SELECT count(*) FROM platform_admin_roles par
              JOIN platform_admins pa ON pa.id = par.platform_admin_id
             WHERE par.platform_role_id = :roleId AND pa.status = 'ACTIVE'""",
            nativeQuery = true)
    long countActiveHoldersByRole(@Param("roleId") UUID roleId);

    /** True iff {@code adminId} holds the role AND is {@code ACTIVE} (an admin that can authenticate). */
    @Query(value = """
            SELECT EXISTS(
                SELECT 1 FROM platform_admin_roles par
                  JOIN platform_admins pa ON pa.id = par.platform_admin_id
                 WHERE par.platform_admin_id = :adminId AND par.platform_role_id = :roleId
                   AND pa.status = 'ACTIVE')""",
            nativeQuery = true)
    boolean isActiveHolder(@Param("adminId") UUID adminId, @Param("roleId") UUID roleId);
}

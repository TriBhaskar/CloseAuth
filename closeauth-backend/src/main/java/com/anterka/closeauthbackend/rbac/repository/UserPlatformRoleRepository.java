package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.UserPlatformRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link UserPlatformRole} join entity. Platform roles are not
 * tenant-scoped; most users have zero rows here.
 */
@Repository
public interface UserPlatformRoleRepository extends JpaRepository<UserPlatformRole, UUID> {

    List<UserPlatformRole> findByUserId(UUID userId);

    List<UserPlatformRole> findByPlatformRoleId(UUID platformRoleId);

    Optional<UserPlatformRole> findByUserIdAndPlatformRoleId(UUID userId, UUID platformRoleId);
}

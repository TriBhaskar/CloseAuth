package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.UserApplicationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link UserApplicationRole} join entity. Used to build the
 * {@code app_roles} token claim for a user.
 */
@Repository
public interface UserApplicationRoleRepository extends JpaRepository<UserApplicationRole, UUID> {

    List<UserApplicationRole> findByUserId(UUID userId);

    List<UserApplicationRole> findByUserIdAndResourceServerId(UUID userId, UUID resourceServerId);

    Optional<UserApplicationRole> findByUserIdAndApplicationRoleId(UUID userId, UUID applicationRoleId);
}

package com.anterka.closeauthbackend.rbac.repository;

import com.anterka.closeauthbackend.rbac.entity.ApplicationRoleScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ApplicationRoleScope} (the application-role scope bundle). Analogous to
 * {@code ResourceServerScopeRepository} in 3c-i: the entity is a within-aggregate child of
 * {@code ApplicationRole}, but direct CRUD needs generated ids and by-role queries. Tenant/RS safety
 * is enforced by the service (it loads the owning application role tenant-scoped first, then scopes
 * every query to that role's id via the {@code applicationRole.id} path).
 */
@Repository
public interface ApplicationRoleScopeRepository extends JpaRepository<ApplicationRoleScope, UUID> {

    boolean existsByApplicationRole_IdAndResourceServerScopeId(UUID applicationRoleId, UUID resourceServerScopeId);

    Optional<ApplicationRoleScope> findByApplicationRole_IdAndResourceServerScopeId(
            UUID applicationRoleId, UUID resourceServerScopeId);

    List<ApplicationRoleScope> findByApplicationRole_Id(UUID applicationRoleId);
}

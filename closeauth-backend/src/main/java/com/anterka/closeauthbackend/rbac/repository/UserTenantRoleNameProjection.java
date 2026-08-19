package com.anterka.closeauthbackend.rbac.repository;

import java.util.UUID;

/**
 * Interface-based projection for {@link UserTenantRoleRepository#findTenantRoleNamesByTenant(UUID)} — one row per
 * (user, held tenant-role name) pair in a tenant, FE-4a's bulk alternative to calling
 * {@code TenantRoleService#getTenantRolesForUser} once per user in a list (N+1). A user holding no tenant roles is
 * simply absent from the result set.
 */
public interface UserTenantRoleNameProjection {

    UUID getUserId();

    String getRoleName();
}

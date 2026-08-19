package com.anterka.closeauthbackend.rbac.repository;

import java.util.UUID;

/**
 * Interface-based projection for {@link ApplicationRoleScopeRepository#countRoleUsageByResourceServer(UUID)} —
 * one row per resource-server scope that is bundled into at least one application role (FE-4b, spec §6.4.4's
 * scope-catalog "where used" column, roles half). A scope bundled into no role is simply absent from the
 * result; there is no zero-count row — same convention as {@code TenantAdminCountProjection}.
 */
public interface ScopeRoleCountProjection {

    UUID getScopeId();

    long getRoleCount();
}

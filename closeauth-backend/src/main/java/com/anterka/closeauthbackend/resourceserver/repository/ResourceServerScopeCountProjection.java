package com.anterka.closeauthbackend.resourceserver.repository;

import java.util.UUID;

/**
 * Interface-based projection for {@link ResourceServerScopeRepository#countScopesByTenant(UUID)} — one row per
 * resource server in the tenant that has at least one scope defined (FE-4b, spec §6.4.4's "Scope count" list
 * column). A resource server with zero scopes is simply absent from the result — same convention as
 * {@code TenantAdminCountProjection}/{@code ScopeRoleCountProjection}.
 */
public interface ResourceServerScopeCountProjection {

    UUID getResourceServerId();

    long getScopeCount();
}

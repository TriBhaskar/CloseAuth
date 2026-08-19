package com.anterka.closeauthbackend.resourceserver.dto;

import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a resource server scope (output DTO). The owning resource-server id is passed in by the
 * caller (the service already knows it) rather than navigated through the lazy {@code @ManyToOne}.
 *
 * @param usedByRoleCount FE-4b (spec §6.4.4's scope-catalog "where used" column) — count of application roles
 *                        bundling this scope. {@code null} means "not computed": this module ({@code
 *                        resourceserver}) has no dependency on {@code rbac} (the reverse edge already exists —
 *                        {@code rbac} depends on {@code resourceserver} for the scope catalog), so callers that
 *                        need this populate it via {@link #withUsedByRoleCount(long)} at the controller layer,
 *                        same convention as {@code TenantView#adminCount}/{@code UserView#roles}. The client
 *                        half of "where used" (roles + clients) is a tracked, deferred gap — see the FE-4b plan
 *                        section for why counting client grants isn't a comparably cheap read.
 */
public record ScopeView(
        UUID id,
        UUID resourceServerId,
        String scopeName,
        String description,
        boolean isDefault,
        boolean requiresConsent,
        Instant createdAt,
        Long usedByRoleCount
) {

    public static ScopeView from(ResourceServerScope scope, UUID resourceServerId) {
        return new ScopeView(
                scope.getId(),
                resourceServerId,
                scope.getScopeName(),
                scope.getDescription(),
                scope.isDefault(),
                scope.isRequiresConsent(),
                scope.getCreatedAt(),
                null
        );
    }

    /** Returns a copy with {@code usedByRoleCount} populated — the merge step callers outside this module perform. */
    public ScopeView withUsedByRoleCount(long usedByRoleCount) {
        return new ScopeView(id, resourceServerId, scopeName, description, isDefault, requiresConsent, createdAt,
                usedByRoleCount);
    }
}

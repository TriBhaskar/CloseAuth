package com.anterka.closeauthbackend.admin.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The centralized, unit-testable admin authorization logic (§7.8/§7.9, Stage 7a) — the security boundary of the whole
 * admin surface. Referenced declaratively by {@link RequiresPlatformAdmin} / {@link RequiresTenantAccess} (via
 * {@code @PreAuthorize}), so 7b's endpoints apply it without re-implementing the checks.
 *
 * <p>Reads the caller's CloseAuth JWT claims:
 * <ul>
 *   <li><b>Platform token</b> (7a): {@code roles} = platform roles, NO {@code tenant_id} — a platform admin.</li>
 *   <li><b>User token</b> (6a): {@code tenant_id} + {@code tenant_roles} — a tenant admin holds {@code TENANT_ADMIN}.</li>
 * </ul>
 *
 * <p><b>The severe bug this prevents:</b> cross-tenant admin access. {@link #hasTenantAccess} grants a tenant admin
 * access ONLY to their own tenant (token {@code tenant_id} == path tenant); a platform admin transcends it.
 */
@Component("adminAuthz")
public class AdminAuthorization {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";

    /** True if the caller's token carries the given platform role. */
    public boolean hasPlatformRole(String role) {
        Jwt jwt = currentJwt();
        return jwt != null && roles(jwt).contains(role);
    }

    /**
     * True if the caller may administer the tenant {@code pathTenantId}: a platform admin (transcends tenant scoping),
     * OR a {@code TENANT_ADMIN} whose token's {@code tenant_id} EXACTLY matches {@code pathTenantId}. A tenant admin of
     * tenant A is denied for tenant B — the cross-tenant admin guard.
     */
    public boolean hasTenantAccess(String pathTenantId) {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return false;
        }
        if (roles(jwt).contains(PLATFORM_ADMIN)) {
            return true; // platform admin can administer any tenant
        }
        String tokenTenantId = jwt.getClaimAsString("tenant_id");
        if (tokenTenantId == null || pathTenantId == null || !tokenTenantId.equals(pathTenantId)) {
            return false; // wrong tenant (or a platform token with no tenant, but no PLATFORM_ADMIN) → denied
        }
        return claimAsStringList(jwt, "tenant_roles").contains(TENANT_ADMIN);
    }

    private List<String> roles(Jwt jwt) {
        return claimAsStringList(jwt, "roles");
    }

    private List<String> claimAsStringList(Jwt jwt, String claim) {
        List<String> value = jwt.getClaimAsStringList(claim);
        return value == null ? List.of() : value;
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }
}

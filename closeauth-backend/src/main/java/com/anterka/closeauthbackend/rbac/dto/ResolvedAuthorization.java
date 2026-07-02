package com.anterka.closeauthbackend.rbac.dto;

import java.util.List;

/**
 * The resolved authorization material for a user within a tenant — the Stage 4 integration point. Stage 4
 * maps this directly into JWT claims per §12; 3c-ii only resolves it ({@code @Transactional(readOnly = true)}),
 * it does not stamp tokens.
 *
 * <p><b>Two identifiers that are easy to conflate — kept distinct here:</b>
 * <ul>
 *   <li>{@link AppRoleGrant#rs()} is the Resource Server's <b>audience URI</b>
 *       (e.g. {@code https://acme.rs.closeauth.io/todomaster-api}) — the {@code aud}/{@code rs} identity.</li>
 *   <li>{@link #scopes()} entries are fully-qualified with the RS <b>slug</b>
 *       (e.g. {@code todomaster-api:read}) — the prefix is the slug, NOT the audience URI. This is the
 *       "prefix at issuance" the Stage 1 bare-scope decision deferred.</li>
 * </ul>
 *
 * @param platformRoles platform-role names → the {@code roles} claim (usually empty)
 * @param tenantRoles   tenant-role names → the {@code tenant_roles} claim
 * @param appRoles      per-RS role grants → the {@code app_roles} claim
 * @param scopes        the effective, fully-qualified {@code {rs_slug}:{scope}} scope set → the {@code scope} claim
 */
public record ResolvedAuthorization(
        List<String> platformRoles,
        List<String> tenantRoles,
        List<AppRoleGrant> appRoles,
        List<String> scopes
) {

    /**
     * @param rs    the Resource Server audience URI (NOT the slug)
     * @param roles the application-role names the user holds for that RS
     */
    public record AppRoleGrant(String rs, List<String> roles) {}
}

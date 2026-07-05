package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.ConsentScopeView;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a requested OAuth scope to a human-readable consent description + its {@code requires_consent} flag
 * (Stage 6b-ii). Two sources:
 * <ul>
 *   <li><b>Platform OIDC scopes</b> ({@code openid}, {@code profile}, {@code email}, {@code offline_access}) — a
 *       CloseAuth-controlled built-in map (NOT tenant-controllable, so their descriptions can't be spoofed). Shown +
 *       approved (require consent).</li>
 *   <li><b>Resource Server scopes</b> ({@code {rs_slug}:{scope}}) — resolved through the tenant's RS catalog
 *       ({@code resource_server_scopes.description} / {@code requires_consent}, 3c-i). The description is tenant-
 *       controlled, but only within that tenant's own trust boundary.</li>
 * </ul>
 * A scope with no resolvable catalog entry shows its raw string and defaults to requiring consent (fail-safe).
 */
@Service
@RequiredArgsConstructor
public class ConsentScopeResolver {

    /** CloseAuth-controlled descriptions for standard OIDC scopes (not tenant-spoofable). */
    private static final Map<String, String> PLATFORM_SCOPES = Map.of(
            "openid", "Verify your identity",
            "profile", "Access your basic profile (name and details)",
            "email", "Access your email address",
            "offline_access", "Stay signed in (refresh access without re-entering your credentials)");

    private final ResourceServerRepository resourceServerRepository;
    private final ResourceServerScopeRepository scopeRepository;

    /** Resolves one scope within a tenant. */
    @Transactional(readOnly = true)
    public ConsentScopeView resolve(UUID tenantId, String scope) {
        String platform = PLATFORM_SCOPES.get(scope);
        if (platform != null) {
            return new ConsentScopeView(scope, platform, true); // standard OIDC scopes are shown + approved
        }
        int colon = scope.indexOf(':');
        if (colon <= 0) {
            return unknown(scope); // not an RS-prefixed scope and not a known platform scope
        }
        String slug = scope.substring(0, colon);
        String bareName = scope.substring(colon + 1);
        Optional<ResourceServer> rs = resourceServerRepository.findBySlugInTenant(slug, tenantId);
        if (rs.isEmpty()) {
            return unknown(scope);
        }
        Optional<ResourceServerScope> catalog =
                scopeRepository.findByResourceServer_IdAndScopeName(rs.get().getId(), bareName);
        if (catalog.isEmpty()) {
            return unknown(scope);
        }
        ResourceServerScope entry = catalog.get();
        String description = entry.getDescription() != null && !entry.getDescription().isBlank()
                ? entry.getDescription() : scope;
        return new ConsentScopeView(scope, description, entry.isRequiresConsent());
    }

    /** Resolves all requested scopes (order-preserving, de-duplicated). */
    @Transactional(readOnly = true)
    public List<ConsentScopeView> resolveAll(UUID tenantId, List<String> scopes) {
        return new LinkedHashSet<>(scopes).stream().map(scope -> resolve(tenantId, scope)).toList();
    }

    /** Whether a scope may be auto-granted without explicit approval ({@code requires_consent = false}). */
    @Transactional(readOnly = true)
    public boolean isAutoGrantable(UUID tenantId, String scope) {
        return !resolve(tenantId, scope).requiresConsent();
    }

    private ConsentScopeView unknown(String scope) {
        // Fail-safe: unknown scopes show their raw string and require explicit consent (never silently auto-granted).
        return new ConsentScopeView(scope, scope, true);
    }
}

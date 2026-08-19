package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.tenant.dto.BrandingView;
import com.anterka.closeauthbackend.tenant.service.TenantBrandingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The <b>public</b> branding resolution endpoint (Stage 6b-ii) — the bridge to the UI stage. The hosted
 * login/consent/registration pages render <em>before</em> the user authenticates, so this endpoint is effectively
 * unauthenticated and MUST expose only non-sensitive branding.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /branding?client_id=...} → 200 {@link BrandingView}
 * {@code {logoUrl, primaryColor, backgroundColor, accentColor, companyName}} — resolved {@code client_id → tenant →
 * branding} with platform defaults for null fields. An unknown {@code client_id} returns platform-default branding
 * (never reveals whether the client exists, and never any tenant internals). The admin branding-management endpoints
 * are Stage 7; only this read resolution lives here.
 *
 * <p><b>{@code client_id} is optional, not required.</b> This endpoint's own contract above promises "always 200,
 * never reveals whether the client exists" — a caller reaching a hosted page without a resolvable {@code client_id}
 * (e.g. a direct/bookmarked navigation to a login page, bypassing the normal resolver flow) must degrade to
 * platform-default branding exactly like an unknown {@code client_id} does, not throw a 500. A blank/absent value is
 * treated identically to an unknown one.
 */
@RestController
@RequiredArgsConstructor
public class BrandingController {

    private final AuthFlowTenantResolver tenantResolver;
    private final TenantBrandingService brandingService;

    @GetMapping(value = "/branding", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BrandingView> resolve(@RequestParam(value = "client_id", required = false) String clientId) {
        BrandingView view = (clientId == null || clientId.isBlank())
                ? brandingService.platformDefault()
                : tenantResolver.resolveTenantId(clientId)
                        .map(brandingService::resolveForTenant)
                        .orElseGet(brandingService::platformDefault);
        return ResponseEntity.ok(view);
    }
}

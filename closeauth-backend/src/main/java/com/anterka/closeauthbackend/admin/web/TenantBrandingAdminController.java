package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.dto.BrandingView;
import com.anterka.closeauthbackend.tenant.dto.UpdateBrandingCommand;
import com.anterka.closeauthbackend.tenant.service.TenantBrandingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated tenant branding management (§7.8) — the tenant-admin editing surface. {@link RequiresTenantAccess}
 * gate. Wraps {@link TenantBrandingService} (6b-ii). <b>Deliberately distinct</b> from 6b-ii's PUBLIC, unauthenticated
 * {@code client_id → branding} resolution endpoint ({@code BrandingController}); the two are NOT merged (authenticated
 * management vs public render).
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/tenants/{tenantId}/branding} · {@code PUT /v1/tenants/{tenantId}/branding} (logo/colors/company-name).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}/branding")
public class TenantBrandingAdminController {

    private final TenantBrandingService tenantBrandingService;

    @GetMapping
    public BrandingView get(@PathVariable String tenantId) {
        return tenantBrandingService.getBranding(ctx(tenantId));
    }

    @PutMapping
    public BrandingView update(@PathVariable String tenantId, @Valid @RequestBody UpdateBrandingCommand command) {
        return tenantBrandingService.updateBranding(ctx(tenantId), command);
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}

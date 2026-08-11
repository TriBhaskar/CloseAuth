package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresPlatformAdmin;
import com.anterka.closeauthbackend.auth.dto.BootstrapAdminCommand;
import com.anterka.closeauthbackend.auth.dto.TempCredentialReissuedView;
import com.anterka.closeauthbackend.auth.dto.TenantAdminBootstrappedView;
import com.anterka.closeauthbackend.auth.service.TenantOnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant-admin onboarding issuance (Phase 3 of the tenant-onboarding design, §2.6/§2.9) — thin HTTP surface over
 * {@link TenantOnboardingService}. Deliberately its own controller, separate from {@link PlatformTenantController}
 * (which is {@code TenantService}-only): keeps the one-service-per-controller shape.
 *
 * <p>Namespaced under {@code /v1/platform/tenants/{tenantId}}, gated {@link RequiresPlatformAdmin} — NOT
 * {@code /v1/tenants/{tenantId}} + {@code RequiresTenantAccess} — so this feature's own surface is
 * platform-admin-only and correctly namespaced by construction (§2.6), independent of {@code hasTenantAccess}'s
 * broader (and separately tracked) cross-tenant bypass. The service calls {@code UserService}/{@code TenantRoleService}
 * directly rather than routing through the public {@code /v1/tenants/{id}/**} surface.
 *
 * <h2>HTTP contract</h2>
 * {@code POST .../bootstrap-admin} (create the tenant's first admin, 201) ·
 * {@code POST .../users/{userId}/reissue-onboarding-credential} (fresh temp credential for an existing,
 * un-rotated admin, 200). Errors: RFC 7807 via {@code ApiExceptionHandler} — notably 409
 * {@code tenant_onboarding.admin_already_exists} / {@code tenant_onboarding.no_pending_temp_credential}.
 */
@RestController
@RequiredArgsConstructor
@RequiresPlatformAdmin
@RequestMapping("/v1/platform/tenants/{tenantId}")
public class PlatformTenantOnboardingController {

    private final TenantOnboardingService tenantOnboardingService;

    @PostMapping("/bootstrap-admin")
    public ResponseEntity<TenantAdminBootstrappedView> bootstrapAdmin(@PathVariable UUID tenantId,
                                                                      @Valid @RequestBody BootstrapAdminCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tenantOnboardingService.bootstrapFirstAdmin(tenantId, command));
    }

    @PostMapping("/users/{userId}/reissue-onboarding-credential")
    public TempCredentialReissuedView reissueOnboardingCredential(@PathVariable UUID tenantId,
                                                                   @PathVariable UUID userId) {
        return tenantOnboardingService.reissueOnboardingCredential(tenantId, userId);
    }
}

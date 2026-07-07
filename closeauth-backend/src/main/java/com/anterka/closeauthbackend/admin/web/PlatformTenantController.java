package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresPlatformAdmin;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Platform-scoped tenant management (§7.8) — cross-tenant operations, so {@link RequiresPlatformAdmin} (a tenant admin
 * cannot reach these). Thin HTTP surface over {@link TenantService} (3a); the state machine + soft-delete already exist.
 * All mutations are audit seams ({@code TODO(stage-8)} in the services).
 *
 * <h2>HTTP contract</h2>
 * {@code POST /v1/platform/tenants} (provision, 201) · {@code GET /v1/platform/tenants?page&size} (paginated) ·
 * {@code GET /v1/platform/tenants/{id}} · {@code POST .../{id}/activate} · {@code POST .../{id}/suspend} ·
 * {@code DELETE .../{id}} (soft-delete). Errors: RFC 7807 via {@code ApiExceptionHandler}.
 */
@RestController
@RequiredArgsConstructor
@RequiresPlatformAdmin
@RequestMapping("/v1/platform/tenants")
public class PlatformTenantController {

    private final TenantService tenantService;

    @PostMapping
    public ResponseEntity<TenantView> provision(@Valid @RequestBody ProvisionTenantCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.provisionTenant(command));
    }

    @GetMapping
    public PageView<TenantView> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return PageView.of(tenantService.listTenants(), page, size);
    }

    @GetMapping("/{id}")
    public TenantView get(@PathVariable UUID id) {
        return tenantService.getTenantById(id);
    }

    @PostMapping("/{id}/activate")
    public TenantView activate(@PathVariable UUID id) {
        return tenantService.activateTenant(id);
    }

    @PostMapping("/{id}/suspend")
    public TenantView suspend(@PathVariable UUID id) {
        return tenantService.suspendTenant(id);
    }

    @DeleteMapping("/{id}")
    public TenantView delete(@PathVariable UUID id) {
        return tenantService.deleteTenant(id); // soft-delete (DELETED, terminal)
    }
}

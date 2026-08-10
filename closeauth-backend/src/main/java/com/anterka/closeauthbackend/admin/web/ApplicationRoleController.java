package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.rbac.dto.ApplicationRoleView;
import com.anterka.closeauthbackend.rbac.dto.CreateApplicationRoleCommand;
import com.anterka.closeauthbackend.rbac.dto.UpdateApplicationRoleCommand;
import com.anterka.closeauthbackend.rbac.service.ApplicationRoleService;
import com.anterka.closeauthbackend.resourceserver.dto.ScopeView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Application-role administration (§7.8) — the RS-scoped role tier: CRUD, scope-bundle management, and assign/revoke to
 * users. {@link RequiresTenantAccess} gate. Wraps {@link ApplicationRoleService} (3c-ii), including its cross-RS
 * scope-bundle validation (surfaced as RFC 7807).
 *
 * <h2>HTTP contract</h2>
 * {@code GET/POST /resource-servers/{rsId}/roles} · {@code GET/PATCH/DELETE .../roles/{roleId}} ·
 * {@code GET .../roles/{roleId}/scopes} · {@code POST/DELETE .../roles/{roleId}/scopes/{scopeId}} ·
 * {@code GET /users/{userId}/application-roles?resourceServerId=} (role names currently held, within one RS) ·
 * {@code POST/DELETE /users/{userId}/application-roles/{roleId}}.
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}")
public class ApplicationRoleController {

    private final ApplicationRoleService applicationRoleService;

    @GetMapping("/resource-servers/{rsId}/roles")
    public PageView<ApplicationRoleView> list(@PathVariable String tenantId, @PathVariable UUID rsId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return PageView.of(applicationRoleService.listByResourceServer(ctx(tenantId), rsId), page, size);
    }

    @PostMapping("/resource-servers/{rsId}/roles")
    public ResponseEntity<ApplicationRoleView> create(@PathVariable String tenantId, @PathVariable UUID rsId,
                                                      @Valid @RequestBody CreateApplicationRoleCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(applicationRoleService.createApplicationRole(ctx(tenantId), rsId, command));
    }

    @GetMapping("/resource-servers/{rsId}/roles/{roleId}")
    public ApplicationRoleView get(@PathVariable String tenantId, @PathVariable UUID rsId, @PathVariable UUID roleId) {
        return applicationRoleService.getApplicationRole(ctx(tenantId), rsId, roleId);
    }

    @PatchMapping("/resource-servers/{rsId}/roles/{roleId}")
    public ApplicationRoleView update(@PathVariable String tenantId, @PathVariable UUID rsId, @PathVariable UUID roleId,
                                      @Valid @RequestBody UpdateApplicationRoleCommand command) {
        return applicationRoleService.updateApplicationRole(ctx(tenantId), rsId, roleId, command);
    }

    @DeleteMapping("/resource-servers/{rsId}/roles/{roleId}")
    public ResponseEntity<Void> delete(@PathVariable String tenantId, @PathVariable UUID rsId,
                                       @PathVariable UUID roleId) {
        applicationRoleService.deleteApplicationRole(ctx(tenantId), rsId, roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resource-servers/{rsId}/roles/{roleId}/scopes")
    public PageView<ScopeView> listScopes(@PathVariable String tenantId, @PathVariable UUID rsId,
                                          @PathVariable UUID roleId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return PageView.of(applicationRoleService.getScopesForRole(ctx(tenantId), roleId), page, size);
    }

    @PostMapping("/resource-servers/{rsId}/roles/{roleId}/scopes/{scopeId}")
    public ResponseEntity<Void> addScope(@PathVariable String tenantId, @PathVariable UUID rsId,
                                         @PathVariable UUID roleId, @PathVariable UUID scopeId) {
        applicationRoleService.addScopeToRole(ctx(tenantId), roleId, scopeId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/resource-servers/{rsId}/roles/{roleId}/scopes/{scopeId}")
    public ResponseEntity<Void> removeScope(@PathVariable String tenantId, @PathVariable UUID rsId,
                                            @PathVariable UUID roleId, @PathVariable UUID scopeId) {
        applicationRoleService.removeScopeFromRole(ctx(tenantId), roleId, scopeId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Application-role names held by {@code userId} within one resource server (UI-3d). Names are unique only per
     * RS ({@code uq_application_roles_rs_name}), so this read is deliberately RS-scoped — a tenant-wide name list
     * would be unjoinable (two RSes in one tenant can share a role name). Mirrors
     * {@link TenantRoleController#rolesForUser}; the caller joins against {@code GET .../{rsId}/roles} for ids.
     * {@code resourceServerId} is required — an omitted value 400s rather than silently answering tenant-wide.
     */
    @GetMapping("/users/{userId}/application-roles")
    public List<String> applicationRolesForUser(@PathVariable String tenantId, @PathVariable UUID userId,
                                                @RequestParam UUID resourceServerId) {
        return applicationRoleService.getApplicationRolesForUser(ctx(tenantId), userId, resourceServerId);
    }

    @PostMapping("/users/{userId}/application-roles/{roleId}")
    public ResponseEntity<Void> assign(@PathVariable String tenantId, @PathVariable UUID userId,
                                       @PathVariable UUID roleId) {
        applicationRoleService.assignApplicationRole(ctx(tenantId), userId, roleId, null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}/application-roles/{roleId}")
    public ResponseEntity<Void> revoke(@PathVariable String tenantId, @PathVariable UUID userId,
                                       @PathVariable UUID roleId) {
        applicationRoleService.revokeApplicationRole(ctx(tenantId), userId, roleId);
        return ResponseEntity.noContent().build();
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}

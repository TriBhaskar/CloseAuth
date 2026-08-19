package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.rbac.dto.CreateTenantRoleCommand;
import com.anterka.closeauthbackend.rbac.dto.TenantRoleView;
import com.anterka.closeauthbackend.rbac.dto.UpdateTenantRoleCommand;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
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
 * Tenant-role administration + assignment (§7.8). {@link RequiresTenantAccess} gate. Wraps {@link TenantRoleService}
 * (3c-ii): system roles are protected and revoking the last {@code TENANT_ADMIN} is refused (409) — both enforced in
 * the service, surfaced as RFC 7807.
 *
 * <h2>HTTP contract</h2>
 * {@code GET/POST /roles} · {@code GET/PATCH/DELETE /roles/{roleId}} ·
 * {@code GET /users/{userId}/tenant-roles} (role names currently held) ·
 * {@code POST/DELETE /users/{userId}/tenant-roles/{roleId}} (assign/revoke).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}")
public class TenantRoleController {

    private final TenantRoleService tenantRoleService;
    private final UserService userService;

    @GetMapping("/roles")
    public PageView<TenantRoleView> list(@PathVariable String tenantId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageView.of(tenantRoleService.listTenantRoles(ctx(tenantId)), page, size);
    }

    @PostMapping("/roles")
    public ResponseEntity<TenantRoleView> create(@PathVariable String tenantId,
                                                 @Valid @RequestBody CreateTenantRoleCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantRoleService.createTenantRole(ctx(tenantId), command));
    }

    @GetMapping("/roles/{roleId}")
    public TenantRoleView get(@PathVariable String tenantId, @PathVariable UUID roleId) {
        return tenantRoleService.getTenantRole(ctx(tenantId), roleId);
    }

    @PatchMapping("/roles/{roleId}")
    public TenantRoleView update(@PathVariable String tenantId, @PathVariable UUID roleId,
                                 @Valid @RequestBody UpdateTenantRoleCommand command) {
        return tenantRoleService.updateTenantRole(ctx(tenantId), roleId, command);
    }

    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> delete(@PathVariable String tenantId, @PathVariable UUID roleId) {
        tenantRoleService.deleteTenantRole(ctx(tenantId), roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * FE-4b (spec §6.4.5): every user currently holding this role. {@code TenantRoleService} returns bare ids
     * (no {@code identity} dependency); resolved to {@link RoleAssigneeView} here, the controller layer, same
     * "decorate at the boundary" convention {@link #list}'s sibling {@code TenantUserController} already uses.
     */
    @GetMapping("/roles/{roleId}/assignees")
    public List<RoleAssigneeView> assignees(@PathVariable String tenantId, @PathVariable UUID roleId) {
        TenantContext context = ctx(tenantId);
        List<UUID> userIds = tenantRoleService.getAssigneeUserIds(context, roleId);
        return userService.getUsersByIds(context, userIds).stream().map(RoleAssigneeView::from).toList();
    }

    /**
     * Role names currently held by {@code userId} in this tenant (UI-3b: the admin console has no other way to know
     * which of the catalog's roles a user already holds before offering assign/revoke). Sorted names, not ids/DTOs —
     * mirrors {@link TenantRoleService#getTenantRolesForUser}; the caller joins against {@code GET /roles} for ids.
     */
    @GetMapping("/users/{userId}/tenant-roles")
    public List<String> rolesForUser(@PathVariable String tenantId, @PathVariable UUID userId) {
        return tenantRoleService.getTenantRolesForUser(ctx(tenantId), userId);
    }

    @PostMapping("/users/{userId}/tenant-roles/{roleId}")
    public ResponseEntity<Void> assign(@PathVariable String tenantId, @PathVariable UUID userId,
                                       @PathVariable UUID roleId) {
        // assignedByUserId=null: the actor may be a platform admin (not a tenant user), so we don't FK to users here.
        tenantRoleService.assignTenantRole(ctx(tenantId), userId, roleId, null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}/tenant-roles/{roleId}")
    public ResponseEntity<Void> revoke(@PathVariable String tenantId, @PathVariable UUID userId,
                                       @PathVariable UUID roleId) {
        tenantRoleService.revokeTenantRole(ctx(tenantId), userId, roleId); // last-admin guard (409) in the service
        return ResponseEntity.noContent().build();
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}

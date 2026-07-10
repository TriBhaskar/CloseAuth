package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.exception.LastTenantAdminException;
import com.anterka.closeauthbackend.common.exception.SystemRoleModificationException;
import com.anterka.closeauthbackend.common.exception.TenantRoleConflictException;
import com.anterka.closeauthbackend.common.exception.TenantRoleNotFoundException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.rbac.dto.CreateTenantRoleCommand;
import com.anterka.closeauthbackend.rbac.dto.TenantRoleView;
import com.anterka.closeauthbackend.rbac.dto.UpdateTenantRoleCommand;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-role tier (§7.9): CRUD for tenant roles (system + custom), assignment/revocation, and the
 * last-admin invariant. Tenant operations, so every method takes {@link TenantContext}.
 *
 * <p><b>System roles</b> ({@code is_system = true}, created by the starter-pack callback) are immutable:
 * update/delete on them throws {@link SystemRoleModificationException}. Custom roles are freely editable.
 */
@Service
@RequiredArgsConstructor
public class TenantRoleService {

    private final TenantRoleRepository tenantRoleRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantService tenantService;
    private final CommandValidator commandValidator;
    private final AuditEmitter auditEmitter;

    // ---- CRUD -------------------------------------------------------------

    @Transactional
    public TenantRoleView createTenantRole(TenantContext context, CreateTenantRoleCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        if (tenantRoleRepository.findByTenantIdAndName(context.tenantId(), command.name()).isPresent()) {
            throw new TenantRoleConflictException(command.name());
        }
        TenantRole role = new TenantRole();
        role.setTenantId(context.tenantId());
        role.setName(command.name());
        role.setDescription(command.description());
        role.setDefault(command.isDefault());
        role.setSystem(false);
        return TenantRoleView.from(tenantRoleRepository.save(role));
    }

    @Transactional
    public TenantRoleView updateTenantRole(TenantContext context, UUID tenantRoleId, UpdateTenantRoleCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        TenantRole role = loadRoleOrThrow(context, tenantRoleId);
        if (role.isSystem()) {
            throw new SystemRoleModificationException(role.getName());
        }
        role.setDescription(command.description());
        role.setDefault(command.isDefault());
        return TenantRoleView.from(role);
    }

    @Transactional
    public void deleteTenantRole(TenantContext context, UUID tenantRoleId) {
        tenantService.requireActiveTenant(context);
        TenantRole role = loadRoleOrThrow(context, tenantRoleId);
        if (role.isSystem()) {
            throw new SystemRoleModificationException(role.getName());
        }
        // Custom role: the DDL cascades its user_tenant_roles assignments.
        tenantRoleRepository.delete(role);
    }

    @Transactional(readOnly = true)
    public TenantRoleView getTenantRole(TenantContext context, UUID tenantRoleId) {
        return TenantRoleView.from(loadRoleOrThrow(context, tenantRoleId));
    }

    @Transactional(readOnly = true)
    public List<TenantRoleView> listTenantRoles(TenantContext context) {
        return tenantRoleRepository.findByTenantId(context.tenantId()).stream()
                .map(TenantRoleView::from)
                .toList();
    }

    // ---- Assignment -------------------------------------------------------

    /** Idempotent: assigning a role the user already holds is a no-op. */
    @Transactional
    public void assignTenantRole(TenantContext context, UUID userId, UUID tenantRoleId, UUID assignedByUserId) {
        tenantService.requireActiveTenant(context);
        loadRoleOrThrow(context, tenantRoleId); // ensures role ∈ tenant
        if (userTenantRoleRepository
                .findByUserIdAndTenantIdAndTenantRoleId(userId, context.tenantId(), tenantRoleId).isPresent()) {
            return;
        }
        UserTenantRole assignment = new UserTenantRole();
        assignment.setUserId(userId);
        assignment.setTenantId(context.tenantId());
        assignment.setTenantRoleId(tenantRoleId);
        assignment.setAssignedByUserId(assignedByUserId);
        userTenantRoleRepository.save(assignment);
        auditEmitter.emit(AuditEvents.roleAssigned(context.tenantId(), userId, "TENANT", tenantRoleId, assignedByUserId));
    }

    /**
     * Revokes a tenant role from a user. Idempotent if the user doesn't hold it.
     *
     * <p><b>Last-admin invariant (§7.1):</b> revoking {@code TENANT_ADMIN} from the last user holding it is
     * refused ({@link LastTenantAdminException}). The count and the delete run in the SAME transaction, so a
     * single revoke can't race itself. A DB constraint can't express "at least one admin", so this
     * transaction-scoped check is the enforcement. Residual race: two <em>concurrent</em> revokes could each
     * observe count == 2 and both proceed — acceptable at Phase 1 scale; a stricter guarantee would need
     * SERIALIZABLE isolation or a tenant-level lock, deferred until needed.
     */
    @Transactional
    public void revokeTenantRole(TenantContext context, UUID userId, UUID tenantRoleId) {
        tenantService.requireActiveTenant(context);
        TenantRole role = loadRoleOrThrow(context, tenantRoleId);

        Optional<UserTenantRole> assignment = userTenantRoleRepository
                .findByUserIdAndTenantIdAndTenantRoleId(userId, context.tenantId(), tenantRoleId);
        if (assignment.isEmpty()) {
            return; // idempotent
        }

        if (SystemRoleNames.TENANT_ADMIN.equals(role.getName())) {
            long admins = userTenantRoleRepository.countByTenantIdAndTenantRoleId(context.tenantId(), tenantRoleId);
            if (admins <= 1) {
                throw new LastTenantAdminException(context.tenantId());
            }
        }
        userTenantRoleRepository.delete(assignment.get());
        auditEmitter.emit(AuditEvents.roleRevoked(context.tenantId(), userId, "TENANT", tenantRoleId));
    }

    @Transactional(readOnly = true)
    public List<String> getTenantRolesForUser(TenantContext context, UUID userId) {
        List<UUID> roleIds = userTenantRoleRepository.findByUserIdAndTenantId(userId, context.tenantId()).stream()
                .map(UserTenantRole::getTenantRoleId)
                .toList();
        return tenantRoleRepository.findAllById(roleIds).stream()
                .filter(r -> r.getTenantId().equals(context.tenantId()))
                .map(TenantRole::getName)
                .sorted()
                .toList();
    }

    // ---- Last-admin guards (for Stage 6/7 user-deletion/suspend flows) ----

    /**
     * Number of users holding {@code TENANT_ADMIN} in the tenant. Provided so 3b's user
     * deletion/suspension flow (orchestrated in Stage 6/7) can refuse an operation that would orphan the
     * tenant. This service does NOT reach into {@code UserService}; that integration is the caller's job.
     */
    @Transactional(readOnly = true)
    public long countTenantAdmins(TenantContext context) {
        return tenantRoleRepository.findByTenantIdAndName(context.tenantId(), SystemRoleNames.TENANT_ADMIN)
                .map(role -> userTenantRoleRepository.countByTenantIdAndTenantRoleId(context.tenantId(), role.getId()))
                .orElse(0L);
    }

    /** True iff {@code userId} is the sole {@code TENANT_ADMIN} of the tenant. */
    @Transactional(readOnly = true)
    public boolean isLastTenantAdmin(TenantContext context, UUID userId) {
        return tenantRoleRepository.findByTenantIdAndName(context.tenantId(), SystemRoleNames.TENANT_ADMIN)
                .map(role -> {
                    boolean holds = userTenantRoleRepository
                            .findByUserIdAndTenantIdAndTenantRoleId(userId, context.tenantId(), role.getId())
                            .isPresent();
                    long admins = userTenantRoleRepository
                            .countByTenantIdAndTenantRoleId(context.tenantId(), role.getId());
                    return holds && admins <= 1;
                })
                .orElse(false);
    }

    private TenantRole loadRoleOrThrow(TenantContext context, UUID tenantRoleId) {
        return tenantRoleRepository.findByIdAndTenantId(tenantRoleId, context.tenantId())
                .orElseThrow(() -> new TenantRoleNotFoundException("id", tenantRoleId));
    }
}

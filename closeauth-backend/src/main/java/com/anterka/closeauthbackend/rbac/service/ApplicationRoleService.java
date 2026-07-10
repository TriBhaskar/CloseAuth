package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.exception.ApplicationRoleConflictException;
import com.anterka.closeauthbackend.common.exception.ApplicationRoleNotFoundException;
import com.anterka.closeauthbackend.common.exception.ResourceServerNotFoundException;
import com.anterka.closeauthbackend.common.exception.ScopeNotFoundException;
import com.anterka.closeauthbackend.common.exception.ScopeResourceServerMismatchException;
import com.anterka.closeauthbackend.common.exception.SystemRoleModificationException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.rbac.dto.ApplicationRoleView;
import com.anterka.closeauthbackend.rbac.dto.CreateApplicationRoleCommand;
import com.anterka.closeauthbackend.rbac.dto.UpdateApplicationRoleCommand;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRoleScope;
import com.anterka.closeauthbackend.rbac.entity.UserApplicationRole;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleScopeRepository;
import com.anterka.closeauthbackend.rbac.repository.UserApplicationRoleRepository;
import com.anterka.closeauthbackend.resourceserver.dto.ScopeView;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application-role tier (§7.9): RS-scoped roles, their scope bundles, and assignment. Permissions-are-scopes:
 * an application role IS a named bundle of the Resource Server's scopes ({@code application_role_scopes} →
 * {@code resource_server_scopes}); there is no separate permission catalog.
 *
 * <p><b>Cross-RS validation:</b> a role for RS-A may only bundle scopes owned by RS-A. {@link #addScopeToRole}
 * verifies the scope's resource server matches the role's, rejecting mismatches.
 */
@Service
@RequiredArgsConstructor
public class ApplicationRoleService {

    private final ApplicationRoleRepository applicationRoleRepository;
    private final ApplicationRoleScopeRepository applicationRoleScopeRepository;
    private final UserApplicationRoleRepository userApplicationRoleRepository;
    private final ResourceServerRepository resourceServerRepository;
    private final ResourceServerScopeRepository resourceServerScopeRepository;
    private final TenantService tenantService;
    private final CommandValidator commandValidator;
    private final AuditEmitter auditEmitter;

    // ---- CRUD (RS-scoped) -------------------------------------------------

    @Transactional
    public ApplicationRoleView createApplicationRole(TenantContext context, UUID resourceServerId,
                                                     CreateApplicationRoleCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        ResourceServer rs = loadResourceServerOrThrow(context, resourceServerId);
        if (applicationRoleRepository.findByResourceServerIdAndName(resourceServerId, command.name()).isPresent()) {
            throw new ApplicationRoleConflictException(resourceServerId, command.name());
        }
        ApplicationRole role = new ApplicationRole();
        role.setResourceServerId(resourceServerId);
        role.setTenantId(rs.getTenantId()); // denormalized from the RS (which is in ctx tenant)
        role.setName(command.name());
        role.setDescription(command.description());
        role.setDefault(command.isDefault());
        role.setSystem(false);
        return ApplicationRoleView.from(applicationRoleRepository.save(role));
    }

    @Transactional
    public ApplicationRoleView updateApplicationRole(TenantContext context, UUID resourceServerId,
                                                     UUID applicationRoleId, UpdateApplicationRoleCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant
        ApplicationRole role = loadRoleInResourceServer(resourceServerId, applicationRoleId);
        if (role.isSystem()) {
            throw new SystemRoleModificationException(role.getName());
        }
        role.setDescription(command.description());
        role.setDefault(command.isDefault());
        return ApplicationRoleView.from(role);
    }

    @Transactional
    public void deleteApplicationRole(TenantContext context, UUID resourceServerId, UUID applicationRoleId) {
        tenantService.requireActiveTenant(context);
        loadResourceServerOrThrow(context, resourceServerId);
        ApplicationRole role = loadRoleInResourceServer(resourceServerId, applicationRoleId);
        if (role.isSystem()) {
            throw new SystemRoleModificationException(role.getName());
        }
        // DDL cascades application_role_scopes and user_application_roles.
        applicationRoleRepository.delete(role);
    }

    @Transactional(readOnly = true)
    public ApplicationRoleView getApplicationRole(TenantContext context, UUID resourceServerId, UUID applicationRoleId) {
        loadResourceServerOrThrow(context, resourceServerId);
        return ApplicationRoleView.from(loadRoleInResourceServer(resourceServerId, applicationRoleId));
    }

    @Transactional(readOnly = true)
    public List<ApplicationRoleView> listByResourceServer(TenantContext context, UUID resourceServerId) {
        loadResourceServerOrThrow(context, resourceServerId);
        return applicationRoleRepository.findByResourceServerId(resourceServerId).stream()
                .map(ApplicationRoleView::from)
                .toList();
    }

    // ---- Scope bundle (permissions-are-scopes) ----------------------------

    /**
     * Bundles a Resource Server scope into an application role. The scope MUST belong to the same Resource
     * Server as the role. Idempotent if already bundled.
     *
     * @throws ScopeResourceServerMismatchException if the scope belongs to a different resource server
     */
    @Transactional
    public void addScopeToRole(TenantContext context, UUID applicationRoleId, UUID resourceServerScopeId) {
        tenantService.requireActiveTenant(context);
        ApplicationRole role = loadRoleInTenant(context, applicationRoleId);
        ResourceServerScope scope = resourceServerScopeRepository.findById(resourceServerScopeId)
                .orElseThrow(() -> new ScopeNotFoundException(role.getResourceServerId(), resourceServerScopeId));

        UUID scopeResourceServerId = scope.getResourceServer().getId();
        if (!scopeResourceServerId.equals(role.getResourceServerId())) {
            throw new ScopeResourceServerMismatchException(role.getResourceServerId(), scopeResourceServerId);
        }
        if (applicationRoleScopeRepository
                .existsByApplicationRole_IdAndResourceServerScopeId(applicationRoleId, resourceServerScopeId)) {
            return; // idempotent
        }
        ApplicationRoleScope link = new ApplicationRoleScope();
        link.setApplicationRole(role);
        link.setResourceServerScopeId(resourceServerScopeId);
        applicationRoleScopeRepository.save(link);
    }

    /** Idempotent: removes the scope from the role's bundle if present. */
    @Transactional
    public void removeScopeFromRole(TenantContext context, UUID applicationRoleId, UUID resourceServerScopeId) {
        tenantService.requireActiveTenant(context);
        loadRoleInTenant(context, applicationRoleId); // ensures role ∈ tenant
        applicationRoleScopeRepository
                .findByApplicationRole_IdAndResourceServerScopeId(applicationRoleId, resourceServerScopeId)
                .ifPresent(applicationRoleScopeRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<ScopeView> getScopesForRole(TenantContext context, UUID applicationRoleId) {
        ApplicationRole role = loadRoleInTenant(context, applicationRoleId);
        List<UUID> scopeIds = applicationRoleScopeRepository.findByApplicationRole_Id(applicationRoleId).stream()
                .map(ApplicationRoleScope::getResourceServerScopeId)
                .toList();
        return resourceServerScopeRepository.findAllById(scopeIds).stream()
                .map(scope -> ScopeView.from(scope, role.getResourceServerId()))
                .toList();
    }

    // ---- Assignment -------------------------------------------------------

    /** Idempotent: assigning a role the user already holds is a no-op. */
    @Transactional
    public void assignApplicationRole(TenantContext context, UUID userId, UUID applicationRoleId, UUID assignedByUserId) {
        tenantService.requireActiveTenant(context);
        ApplicationRole role = loadRoleInTenant(context, applicationRoleId);
        if (userApplicationRoleRepository.findByUserIdAndApplicationRoleId(userId, applicationRoleId).isPresent()) {
            return;
        }
        UserApplicationRole assignment = new UserApplicationRole();
        assignment.setUserId(userId);
        assignment.setResourceServerId(role.getResourceServerId());
        assignment.setApplicationRoleId(applicationRoleId);
        assignment.setTenantId(context.tenantId());
        assignment.setAssignedByUserId(assignedByUserId);
        userApplicationRoleRepository.save(assignment);
        auditEmitter.emit(AuditEvents.roleAssigned(context.tenantId(), userId, "APPLICATION", applicationRoleId,
                assignedByUserId));
    }

    /** Idempotent: revoking a role the user does not hold is a no-op. */
    @Transactional
    public void revokeApplicationRole(TenantContext context, UUID userId, UUID applicationRoleId) {
        tenantService.requireActiveTenant(context);
        loadRoleInTenant(context, applicationRoleId); // ensures role ∈ tenant
        userApplicationRoleRepository.findByUserIdAndApplicationRoleId(userId, applicationRoleId)
                .ifPresent(existing -> {
                    userApplicationRoleRepository.delete(existing);
                    auditEmitter.emit(AuditEvents.roleRevoked(context.tenantId(), userId, "APPLICATION",
                            applicationRoleId));
                });
    }

    @Transactional(readOnly = true)
    public List<String> getApplicationRolesForUser(TenantContext context, UUID userId, UUID resourceServerId) {
        loadResourceServerOrThrow(context, resourceServerId);
        List<UUID> roleIds = userApplicationRoleRepository
                .findByUserIdAndResourceServerId(userId, resourceServerId).stream()
                .map(UserApplicationRole::getApplicationRoleId)
                .toList();
        return applicationRoleRepository.findAllById(roleIds).stream()
                .filter(r -> r.getTenantId().equals(context.tenantId()))
                .map(ApplicationRole::getName)
                .sorted()
                .toList();
    }

    // ---- Internals --------------------------------------------------------

    private ResourceServer loadResourceServerOrThrow(TenantContext context, UUID resourceServerId) {
        return resourceServerRepository.findByIdInTenant(resourceServerId, context.tenantId())
                .orElseThrow(() -> new ResourceServerNotFoundException("id", resourceServerId));
    }

    private ApplicationRole loadRoleInResourceServer(UUID resourceServerId, UUID applicationRoleId) {
        return applicationRoleRepository.findByIdAndResourceServerId(applicationRoleId, resourceServerId)
                .orElseThrow(() -> new ApplicationRoleNotFoundException("id", applicationRoleId));
    }

    private ApplicationRole loadRoleInTenant(TenantContext context, UUID applicationRoleId) {
        return applicationRoleRepository.findByIdAndTenantId(applicationRoleId, context.tenantId())
                .orElseThrow(() -> new ApplicationRoleNotFoundException("id", applicationRoleId));
    }
}

package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.exception.PlatformRoleNotFoundException;
import com.anterka.closeauthbackend.rbac.dto.PlatformRoleView;
import com.anterka.closeauthbackend.rbac.entity.PlatformRole;
import com.anterka.closeauthbackend.rbac.entity.UserPlatformRole;
import com.anterka.closeauthbackend.rbac.repository.PlatformRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserPlatformRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Platform-role tier (§7.9): assigns/revokes the seeded platform roles ({@code PLATFORM_ADMIN},
 * {@code PLATFORM_SUPPORT}) to users. The role <em>definitions</em> are reference data seeded by
 * {@code V2__seed_platform_roles.sql}; this service does not create them.
 *
 * <p><b>Bare {@code UUID}, not {@code TenantContext}, by design.</b> Platform roles are NOT tenant-scoped —
 * they concern CloseAuth-the-platform. Per the "acted-on vs operated-within" rule, a platform operation is not
 * "within a tenant", so it takes a bare user {@code UUID} and role name, never a {@code TenantContext}.
 */
@Service
@RequiredArgsConstructor
public class PlatformRoleService {

    private final PlatformRoleRepository platformRoleRepository;
    private final UserPlatformRoleRepository userPlatformRoleRepository;

    /** Idempotent: assigning a role the user already holds is a no-op. */
    @Transactional
    public void assignPlatformRole(UUID userId, String platformRoleName, UUID grantedByUserId) {
        PlatformRole role = requireRole(platformRoleName);
        if (userPlatformRoleRepository.findByUserIdAndPlatformRoleId(userId, role.getId()).isPresent()) {
            return;
        }
        UserPlatformRole assignment = new UserPlatformRole();
        assignment.setUserId(userId);
        assignment.setPlatformRoleId(role.getId());
        assignment.setGrantedByUserId(grantedByUserId);
        userPlatformRoleRepository.save(assignment);
    }

    /** Idempotent: revoking a role the user does not hold is a no-op. */
    @Transactional
    public void revokePlatformRole(UUID userId, String platformRoleName) {
        PlatformRole role = requireRole(platformRoleName);
        userPlatformRoleRepository.findByUserIdAndPlatformRoleId(userId, role.getId())
                .ifPresent(userPlatformRoleRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<String> getPlatformRolesForUser(UUID userId) {
        List<UUID> roleIds = userPlatformRoleRepository.findByUserId(userId).stream()
                .map(UserPlatformRole::getPlatformRoleId)
                .toList();
        return platformRoleRepository.findAllById(roleIds).stream()
                .map(PlatformRole::getName)
                .sorted()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PlatformRoleView> listPlatformRoles() {
        return platformRoleRepository.findAll().stream()
                .map(PlatformRoleView::from)
                .toList();
    }

    private PlatformRole requireRole(String name) {
        return platformRoleRepository.findByName(name)
                .orElseThrow(() -> new PlatformRoleNotFoundException(name));
    }
}

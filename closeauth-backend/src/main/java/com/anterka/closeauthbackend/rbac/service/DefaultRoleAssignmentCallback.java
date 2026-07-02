package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.service.UserProvisioningCallback;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Implements 3b's {@link UserProvisioningCallback} seam: when a user is created, assign the tenant's default
 * tenant role(s) ({@code is_default = true} → {@code TENANT_MEMBER}). Runs inside the user-creation transaction.
 *
 * <p>Uses the repositories directly (not {@link TenantRoleService}) to avoid re-entrant guard checks — the
 * tenant is already known-active (user creation checked it) and this is an internal system assignment.
 *
 * <p><b>No-default-role defensive decision:</b> a tenant should always have a default role (the starter-pack
 * creates {@code TENANT_MEMBER} as default). If none is found — a sign of broken provisioning — we
 * <b>log a WARN and proceed</b> rather than fail the signup: a role-less user is recoverable, a failed signup is
 * worse UX. The WARN makes the anomaly visible.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultRoleAssignmentCallback implements UserProvisioningCallback {

    private final TenantRoleRepository tenantRoleRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;

    @Override
    public void onUserProvisioned(User user, TenantContext context) {
        List<TenantRole> defaults = tenantRoleRepository.findByTenantIdAndIsDefaultTrue(user.getTenantId());
        if (defaults.isEmpty()) {
            log.warn("No default tenant role found for tenant {}; user {} created without a default role",
                    user.getTenantId(), user.getId());
            return;
        }
        // Multiple default roles is INTENTIONAL and supported (do not "correct" this to a single-default
        // assumption): a tenant may legitimately want new users to receive TENANT_MEMBER plus a custom
        // onboarding role. The starter-pack marks only TENANT_MEMBER default, so the common case is one role.
        for (TenantRole role : defaults) {
            UserTenantRole assignment = new UserTenantRole();
            assignment.setUserId(user.getId());
            assignment.setTenantId(user.getTenantId());
            assignment.setTenantRoleId(role.getId());
            assignment.setAssignedByUserId(null); // system-assigned at provisioning
            userTenantRoleRepository.save(assignment);
        }
    }
}

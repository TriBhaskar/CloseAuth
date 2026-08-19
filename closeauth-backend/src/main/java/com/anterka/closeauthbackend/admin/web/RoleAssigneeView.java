package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;

import java.util.UUID;

/**
 * FE-4b: one row of a role's assignees list (spec §6.4.5 — "role detail shows assignees"). Lives in
 * {@code admin.web}, not {@code rbac.dto} or {@code identity.dto}: it's built from a {@link UserView} for a
 * {@code rbac} concept (a role's holders), and putting it in either owning domain would create a new
 * cross-module dependency edge neither currently has. {@code admin.web} already legitimately depends on both
 * (see {@code TenantUserController}/{@code TenantRoleController}), so this is the natural, dependency-neutral
 * home — a deliberately smaller projection of {@link UserView} than the full type, since a role-assignees list
 * has no use for most of its fields.
 */
public record RoleAssigneeView(
        UUID userId,
        String email,
        String firstName,
        String lastName,
        UserStatus status
) {

    public static RoleAssigneeView from(UserView user) {
        return new RoleAssigneeView(user.id(), user.email(), user.firstName(), user.lastName(), user.status());
    }
}

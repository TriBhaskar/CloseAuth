package com.anterka.closeauthbackend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * FE-4a: tenant-admin-scoped sibling of {@code BootstrapAdminCommand} — creates an ordinary tenant user (not
 * necessarily an admin) with a system-generated temporary credential, per spec §6.4.2's "set a temporary password"
 * create mode. Unlike {@code bootstrapFirstAdmin}, this does NOT send an email and does NOT need a client id: the
 * created user's {@code must_change_password=true} flag alone is sufficient — whichever client they next log in
 * through, {@code LoginPolicyService} gates the attempt and {@code LoginController} routes them into forced
 * rotation with the real {@code client_id} already in hand. {@code initialRoleId} is optional — when present, the
 * role is assigned in the SAME transaction as user creation, so "created, but the role grant failed" is not an
 * observable half-state.
 */
public record CreateUserWithTempCredentialCommand(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName,

        @Size(max = 20)
        String phone,

        UUID initialRoleId

) {}

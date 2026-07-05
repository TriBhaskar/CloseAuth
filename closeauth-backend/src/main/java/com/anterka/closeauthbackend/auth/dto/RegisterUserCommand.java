package com.anterka.closeauthbackend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-registration input (Stage 6b-i). Registration is tenant-scoped (the tenant is resolved from the authorization
 * request's {@code client_id}, as in 6a) — the same email in different tenants is a different user (3b).
 *
 * @param email        the registrant's email (normalized + uniqueness-checked per tenant by {@code UserService})
 * @param password     the initial password
 * @param firstName    optional
 * @param lastName     optional
 * @param phone        optional
 * @param inviteToken  required only in INVITE_ONLY mode (the raw invite token from the invitation link); ignored otherwise
 */
public record RegisterUserCommand(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        String firstName,
        String lastName,
        String phone,
        String inviteToken) {
}

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
 * @param clientId     FE-2d: system-derived (from the request's own {@code client_id} param, not user input) — carried
 *                     so {@code EmailVerifiedRegistrationStrategy} can build a tenant-namespaced verification link
 *                     without widening {@code RegistrationStrategy}'s shared interface for the one mode that needs it.
 *                     Never validated (not user-authored); null only in a test double that doesn't care.
 * @param tenantSlug   FE-2d: system-derived, resolved by {@code RegistrationController} alongside {@code clientId} —
 *                     same reasoning.
 */
public record RegisterUserCommand(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        String firstName,
        String lastName,
        String phone,
        String inviteToken,
        String clientId,
        String tenantSlug) {
}

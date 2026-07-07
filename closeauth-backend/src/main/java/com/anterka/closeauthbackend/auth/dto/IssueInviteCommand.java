package com.anterka.closeauthbackend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to issue an invitation (§7.8, INVITE_ONLY registration) — {@code POST /v1/tenants/{tid}/invites}. The email
 * receives the opaque {@code INVITE} one-time token (6b-i's primitive); registration then consumes it.
 */
public record IssueInviteCommand(@NotBlank @Email @Size(max = 255) String email) {
}

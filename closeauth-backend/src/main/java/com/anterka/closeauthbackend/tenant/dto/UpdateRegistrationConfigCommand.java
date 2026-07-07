package com.anterka.closeauthbackend.tenant.dto;

import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import jakarta.validation.constraints.NotNull;

/**
 * Command to set a tenant's registration mode (§9.2) — {@code PUT /v1/tenants/{tid}/registration-config}. The mode is
 * the policy: {@code OPEN | EMAIL_VERIFIED | ADMIN_APPROVED | INVITE_ONLY} (validated by the enum binding).
 */
public record UpdateRegistrationConfigCommand(@NotNull RegistrationMode mode) {
}

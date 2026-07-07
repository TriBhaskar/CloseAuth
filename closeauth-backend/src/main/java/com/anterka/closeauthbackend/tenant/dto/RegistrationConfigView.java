package com.anterka.closeauthbackend.tenant.dto;

import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;

import java.util.UUID;

/**
 * Read view of a tenant's registration configuration (§9.2) — the mode a tenant admin sets via
 * {@code GET/PUT /v1/tenants/{tid}/registration-config}. Output DTO; no internal fields.
 */
public record RegistrationConfigView(UUID tenantId, RegistrationMode mode) {
}

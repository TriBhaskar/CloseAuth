package com.anterka.closeauthbackend.tenant.enums;

/**
 * Tenant lifecycle states (Section 7.1). Values must match the CHECK constraint
 * on {@code tenants.status} exactly.
 */
public enum TenantStatus {
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    DELETED
}

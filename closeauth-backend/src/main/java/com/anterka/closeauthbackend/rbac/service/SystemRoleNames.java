package com.anterka.closeauthbackend.rbac.service;

/**
 * Canonical names of the system (CloseAuth-provided) roles, shared across the RBAC services, the
 * starter-pack callback, and the {@code V2} platform-role seed. Single source of truth so the
 * starter-pack, the last-admin check, and the seed can never drift apart.
 */
public final class SystemRoleNames {

    // Platform tier (seeded by V2__seed_platform_roles.sql)
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String PLATFORM_SUPPORT = "PLATFORM_SUPPORT";

    // Tenant tier (created per-tenant by the starter-pack callback)
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String TENANT_MEMBER = "TENANT_MEMBER";
    public static final String BILLING_ADMIN = "BILLING_ADMIN";

    private SystemRoleNames() {
    }
}

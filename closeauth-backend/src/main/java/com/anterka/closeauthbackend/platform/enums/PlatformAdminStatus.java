package com.anterka.closeauthbackend.platform.enums;

/**
 * Lifecycle status of a {@link com.anterka.closeauthbackend.platform.entity.PlatformAdmin} (§7.8). Values must match
 * the {@code platform_admins.status} CHECK constraint exactly. Only {@code ACTIVE} admins may authenticate.
 */
public enum PlatformAdminStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}

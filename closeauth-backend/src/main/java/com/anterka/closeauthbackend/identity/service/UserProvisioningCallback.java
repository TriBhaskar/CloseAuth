package com.anterka.closeauthbackend.identity.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.entity.User;

/**
 * Extension seam invoked once, synchronously, right after a user is created and within the same
 * creation transaction — the identity-domain analogue of 3a's {@code TenantProvisioningCallback}.
 *
 * <p><b>Stage 3c hook:</b> the RBAC module provides a {@code @Component} implementing this to assign
 * the tenant's default role(s) to the new user. Because {@link UserService} injects <em>all</em> beans
 * of this type, 3c only adds its implementation — no {@code UserService} change is required. In 3b
 * there are zero implementations, so the seam is a no-op.
 *
 * <p>Internal within-transaction SPI (not the public DTO API), so it receives the live {@link User}
 * entity plus its {@link TenantContext}; the "no entity leakage" convention governs the external boundary.
 */
public interface UserProvisioningCallback {

    void onUserProvisioned(User user, TenantContext context);
}

package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * Update or delete was requested for the tenant's auto-provisioned {@code admin-console-{slug}} client
 * (see {@code AdminConsoleClientProvisioningCallback}) — refused because the tenant admin console
 * authenticates through this exact client; editing its redirect URI or deleting it outright would lock the
 * tenant's admins out of their own console with no self-service recovery path. Category
 * {@link ErrorCategory#CONFLICT}, mirroring {@link ResourceServerDeletionNotAllowedException}'s posture on
 * the analogous auto-created-resource-server case.
 */
public class ClientPlatformManagedException extends CloseAuthDomainException {

    private static final String CODE = "client.platform_managed";

    public ClientPlatformManagedException(String clientId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Client is platform-managed (the tenant admin console) and cannot be updated or deleted: " + clientId,
                Map.of("clientId", clientId));
    }
}

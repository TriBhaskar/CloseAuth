package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * The operation would leave CloseAuth with zero active {@code PLATFORM_ADMIN} holders, which is forbidden (§7.8) —
 * the platform analogue of {@link LastTenantAdminException}. {@code PLATFORM_ADMIN} is the role
 * {@code @RequiresPlatformAdmin} actually checks, so losing the last active holder would lock the platform surface out.
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class LastPlatformAdminException extends CloseAuthDomainException {

    private static final String CODE = "platform_admin.last_admin";

    public LastPlatformAdminException(UUID platformAdminId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Cannot remove the last active PLATFORM_ADMIN",
                Map.of("platformAdminId", platformAdminId.toString()));
    }
}

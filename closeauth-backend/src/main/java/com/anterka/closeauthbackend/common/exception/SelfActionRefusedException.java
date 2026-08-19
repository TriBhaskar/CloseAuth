package com.anterka.closeauthbackend.common.exception;

import java.util.Map;
import java.util.UUID;

/**
 * A platform admin targeted a self-lockout-capable operation at their own account — suspending
 * themselves, or revoking their own {@code PLATFORM_ADMIN} role (§7.8). The last-admin invariant
 * ({@link LastPlatformAdminException}) only guarantees *someone* retains access; this guards that
 * the ACTING admin doesn't lock themselves out of their own live session, independent of how many
 * other admins remain. Category {@link ErrorCategory#FORBIDDEN}.
 */
public class SelfActionRefusedException extends CloseAuthDomainException {

    private static final String CODE = "platform_admin.self_action_refused";

    public SelfActionRefusedException(UUID platformAdminId) {
        super(ErrorCategory.FORBIDDEN, CODE,
                "This action cannot be performed on your own account",
                Map.of("platformAdminId", platformAdminId.toString()));
    }
}

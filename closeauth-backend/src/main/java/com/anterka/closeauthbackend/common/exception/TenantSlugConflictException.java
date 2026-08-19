package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * The server could not generate a unique Tenant ID after exhausting its collision-retry budget
 * ({@link com.anterka.closeauthbackend.tenant.service.TenantSlugGenerator}). Tenant IDs are
 * globally unique and server-derived (spec §1.2) — this is not an operator-facing "that ID is
 * taken" error (there is no operator-authored ID to conflict), just a near-unreachable exhaustion
 * of the random-suffix retry loop. Category {@link ErrorCategory#CONFLICT}.
 */
public class TenantSlugConflictException extends CloseAuthDomainException {

    private static final String CODE = "tenant.slug_conflict";

    public TenantSlugConflictException(String lastAttemptedSlug) {
        super(ErrorCategory.CONFLICT, CODE,
                "Could not generate a unique Tenant ID: " + lastAttemptedSlug,
                Map.of("slug", lastAttemptedSlug));
    }
}

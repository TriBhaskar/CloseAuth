package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A tenant slug is already taken. Slugs are globally unique. Category
 * {@link ErrorCategory#CONFLICT}.
 */
public class TenantSlugConflictException extends CloseAuthDomainException {

    private static final String CODE = "tenant.slug_conflict";

    public TenantSlugConflictException(String slug) {
        super(ErrorCategory.CONFLICT, CODE,
                "Tenant slug already in use: " + slug,
                Map.of("slug", slug));
    }
}

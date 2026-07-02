package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A resource server slug is already taken <em>within the tenant</em> ({@code UNIQUE (tenant_id, slug)}).
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class ResourceServerSlugConflictException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.slug_conflict";

    public ResourceServerSlugConflictException(String slug) {
        super(ErrorCategory.CONFLICT, CODE,
                "Resource server slug already in use in this tenant: " + slug,
                Map.of("slug", slug));
    }
}

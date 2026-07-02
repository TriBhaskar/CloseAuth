package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A tenant could not be found by the given lookup key. Category {@link ErrorCategory#NOT_FOUND}.
 */
public class TenantNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "tenant.not_found";

    /**
     * @param lookupField the field the tenant was looked up by (e.g. {@code "id"}, {@code "slug"})
     * @param lookupValue the value that produced no match
     */
    public TenantNotFoundException(String lookupField, Object lookupValue) {
        super(ErrorCategory.NOT_FOUND, CODE,
                "Tenant not found for " + lookupField + "=" + lookupValue,
                Map.of(lookupField, String.valueOf(lookupValue)));
    }
}

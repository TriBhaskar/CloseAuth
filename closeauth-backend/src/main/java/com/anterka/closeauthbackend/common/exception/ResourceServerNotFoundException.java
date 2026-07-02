package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A resource server could not be found (within the tenant scope, or by its globally-unique
 * audience identifier). Category {@link ErrorCategory#NOT_FOUND}.
 */
public class ResourceServerNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.not_found";

    public ResourceServerNotFoundException(String lookupField, Object lookupValue) {
        super(ErrorCategory.NOT_FOUND, CODE,
                "Resource server not found for " + lookupField + "=" + lookupValue,
                Map.of(lookupField, String.valueOf(lookupValue)));
    }
}

package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A user could not be found (within the tenant scope). Category
 * {@link ErrorCategory#NOT_FOUND}.
 */
public class UserNotFoundException extends CloseAuthDomainException {

    private static final String CODE = "user.not_found";

    public UserNotFoundException(String lookupField, Object lookupValue) {
        super(ErrorCategory.NOT_FOUND, CODE,
                "User not found for " + lookupField + "=" + lookupValue,
                Map.of(lookupField, String.valueOf(lookupValue)));
    }
}

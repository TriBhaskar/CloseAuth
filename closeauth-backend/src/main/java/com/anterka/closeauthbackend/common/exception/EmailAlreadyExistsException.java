package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * The (normalized) email is already registered <em>within the same tenant</em>. Email is unique
 * per {@code (tenant_id, email)}, not globally (Section 11) — the same email in a different tenant
 * is NOT a conflict. Category {@link ErrorCategory#CONFLICT}.
 */
public class EmailAlreadyExistsException extends CloseAuthDomainException {

    private static final String CODE = "user.email_conflict";

    public EmailAlreadyExistsException(String email) {
        super(ErrorCategory.CONFLICT, CODE,
                "Email already registered in this tenant: " + email,
                Map.of("email", email));
    }
}

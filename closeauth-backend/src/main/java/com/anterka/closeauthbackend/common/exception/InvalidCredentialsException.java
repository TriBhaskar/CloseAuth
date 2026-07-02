package com.anterka.closeauthbackend.common.exception;

/**
 * The supplied credentials are invalid in an <em>authenticated, self-service</em> context — e.g.
 * a wrong current password during {@code changePassword}. Category {@link ErrorCategory#FORBIDDEN}.
 *
 * <p>Deliberately carries a generic message and NO context, so it never reveals which factor failed.
 *
 * <p>Note: the login-time {@code verifyPassword} primitive does NOT throw this (throwing would leak
 * user-enumeration signal); it returns a uniform failure result instead. This exception is only for
 * flows where the caller is already the authenticated account owner.
 */
public class InvalidCredentialsException extends CloseAuthDomainException {

    private static final String CODE = "user.invalid_credentials";

    public InvalidCredentialsException() {
        super(ErrorCategory.FORBIDDEN, CODE, "Invalid credentials");
    }
}

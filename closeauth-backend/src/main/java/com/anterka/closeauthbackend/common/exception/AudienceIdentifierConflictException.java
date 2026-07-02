package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * A resource server audience identifier is already taken. Audiences are <em>globally</em> unique
 * ({@code UNIQUE (audience_identifier)}) so the token {@code aud} claim is unambiguous. Category
 * {@link ErrorCategory#CONFLICT}.
 */
public class AudienceIdentifierConflictException extends CloseAuthDomainException {

    private static final String CODE = "resource_server.audience_conflict";

    public AudienceIdentifierConflictException(String audienceIdentifier) {
        super(ErrorCategory.CONFLICT, CODE,
                "Audience identifier already in use: " + audienceIdentifier,
                Map.of("audienceIdentifier", audienceIdentifier));
    }
}

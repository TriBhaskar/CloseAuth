package com.anterka.closeauthbackend.common.exception;

import com.anterka.closeauthbackend.tenant.enums.TenantStatus;

import java.util.Map;

/**
 * An attempted tenant lifecycle transition is not permitted by the state machine.
 * Category {@link ErrorCategory#STATE}. Names the from- and to-states so callers
 * (and Stage 7 error responses) can report exactly what was rejected.
 */
public class InvalidTenantStateTransitionException extends CloseAuthDomainException {

    private static final String CODE = "tenant.invalid_state_transition";

    public InvalidTenantStateTransitionException(TenantStatus from, TenantStatus to) {
        super(ErrorCategory.STATE, CODE,
                "Illegal tenant state transition: " + from + " -> " + to,
                Map.of("from", from.name(), "to", to.name()));
    }
}

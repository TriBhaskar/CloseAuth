package com.anterka.closeauthbackend.common.exception;

import com.anterka.closeauthbackend.identity.enums.UserStatus;

import java.util.Map;

/**
 * An attempted user lifecycle transition is not permitted by the user state machine. Category
 * {@link ErrorCategory#STATE}.
 */
public class InvalidUserStateTransitionException extends CloseAuthDomainException {

    private static final String CODE = "user.invalid_state_transition";

    public InvalidUserStateTransitionException(UserStatus from, UserStatus to) {
        super(ErrorCategory.STATE, CODE,
                "Illegal user state transition: " + from + " -> " + to,
                Map.of("from", from.name(), "to", to.name()));
    }
}

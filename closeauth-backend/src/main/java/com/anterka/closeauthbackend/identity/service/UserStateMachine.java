package com.anterka.closeauthbackend.identity.service;

import com.anterka.closeauthbackend.common.exception.InvalidUserStateTransitionException;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single, auditable source of truth for allowed {@link UserStatus} transitions. Mirrors the 3a
 * {@code TenantStateMachine} style (centralized matrix, not scattered {@code if} checks), because
 * user status has real rules worth centralizing.
 *
 * <pre>
 *   PENDING   → ACTIVE, DELETED
 *   ACTIVE    → SUSPENDED, DELETED
 *   SUSPENDED → ACTIVE, DELETED
 *   DELETED   → (terminal — nothing; you cannot operate on a DELETED user)
 * </pre>
 */
@Component
public class UserStateMachine {

    private static final Map<UserStatus, Set<UserStatus>> ALLOWED;

    static {
        EnumMap<UserStatus, Set<UserStatus>> m = new EnumMap<>(UserStatus.class);
        m.put(UserStatus.PENDING, EnumSet.of(UserStatus.ACTIVE, UserStatus.DELETED));
        m.put(UserStatus.ACTIVE, EnumSet.of(UserStatus.SUSPENDED, UserStatus.DELETED));
        m.put(UserStatus.SUSPENDED, EnumSet.of(UserStatus.ACTIVE, UserStatus.DELETED));
        m.put(UserStatus.DELETED, EnumSet.noneOf(UserStatus.class));
        ALLOWED = m;
    }

    public boolean isAllowed(UserStatus from, UserStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(UserStatus.class)).contains(to);
    }

    public void checkTransition(UserStatus from, UserStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidUserStateTransitionException(from, to);
        }
    }
}

package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.exception.InvalidTenantStateTransitionException;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single, auditable source of truth for allowed {@link TenantStatus} transitions
 * (Section 7.1). Centralized here so the transition rules live in one place rather than
 * being scattered as {@code if (status == …)} checks across the service.
 *
 * <p>Allowed transitions:
 * <pre>
 *   PROVISIONING → ACTIVE, DELETED
 *   ACTIVE       → SUSPENDED, DELETED
 *   SUSPENDED    → ACTIVE, DELETED
 *   DELETED      → (terminal — nothing)
 * </pre>
 * Notably: nothing transitions out of DELETED, a PROVISIONING tenant cannot be suspended,
 * and same-state "transitions" (e.g. ACTIVE → ACTIVE) are not allowed.
 */
@Component
public class TenantStateMachine {

    private static final Map<TenantStatus, Set<TenantStatus>> ALLOWED;

    static {
        EnumMap<TenantStatus, Set<TenantStatus>> m = new EnumMap<>(TenantStatus.class);
        m.put(TenantStatus.PROVISIONING, EnumSet.of(TenantStatus.ACTIVE, TenantStatus.DELETED));
        m.put(TenantStatus.ACTIVE, EnumSet.of(TenantStatus.SUSPENDED, TenantStatus.DELETED));
        m.put(TenantStatus.SUSPENDED, EnumSet.of(TenantStatus.ACTIVE, TenantStatus.DELETED));
        m.put(TenantStatus.DELETED, EnumSet.noneOf(TenantStatus.class));
        ALLOWED = m;
    }

    /** @return whether {@code from → to} is a permitted transition. */
    public boolean isAllowed(TenantStatus from, TenantStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TenantStatus.class)).contains(to);
    }

    /**
     * Asserts {@code from → to} is permitted.
     *
     * @throws InvalidTenantStateTransitionException if it is not
     */
    public void checkTransition(TenantStatus from, TenantStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidTenantStateTransitionException(from, to);
        }
    }
}

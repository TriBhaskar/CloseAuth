package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.exception.InvalidTenantStateTransitionException;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive test of the tenant transition matrix. Covers all {@code 4 × 4}
 * (from, to) pairs against the single allowed-set, so no permitted transition is
 * missing and no forbidden one slips through.
 */
class TenantStateMachineTest {

    private final TenantStateMachine stateMachine = new TenantStateMachine();

    /** The complete set of permitted transitions per Section 7.1. */
    private static final Set<Map.Entry<TenantStatus, TenantStatus>> ALLOWED = Set.of(
            Map.entry(TenantStatus.PROVISIONING, TenantStatus.ACTIVE),
            Map.entry(TenantStatus.PROVISIONING, TenantStatus.DELETED),
            Map.entry(TenantStatus.ACTIVE, TenantStatus.SUSPENDED),
            Map.entry(TenantStatus.ACTIVE, TenantStatus.DELETED),
            Map.entry(TenantStatus.SUSPENDED, TenantStatus.ACTIVE),
            Map.entry(TenantStatus.SUSPENDED, TenantStatus.DELETED)
    );

    @Test
    void everyTransitionPairMatchesTheAllowedMatrix() {
        for (TenantStatus from : TenantStatus.values()) {
            for (TenantStatus to : TenantStatus.values()) {
                boolean expected = ALLOWED.contains(Map.entry(from, to));

                assertThat(stateMachine.isAllowed(from, to))
                        .as("isAllowed(%s -> %s)", from, to)
                        .isEqualTo(expected);

                if (expected) {
                    assertThatCode(() -> stateMachine.checkTransition(from, to))
                            .as("checkTransition(%s -> %s) should pass", from, to)
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> stateMachine.checkTransition(from, to))
                            .as("checkTransition(%s -> %s) should fail", from, to)
                            .isInstanceOf(InvalidTenantStateTransitionException.class);
                }
            }
        }
    }

    @Test
    void deletedIsTerminal() {
        for (TenantStatus to : TenantStatus.values()) {
            assertThat(stateMachine.isAllowed(TenantStatus.DELETED, to))
                    .as("DELETED -> %s must be forbidden", to)
                    .isFalse();
        }
    }

    @Test
    void sameStateTransitionsAreForbidden() {
        for (TenantStatus s : TenantStatus.values()) {
            assertThat(stateMachine.isAllowed(s, s))
                    .as("%s -> %s (no-op) must be forbidden", s, s)
                    .isFalse();
        }
    }
}

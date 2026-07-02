package com.anterka.closeauthbackend.identity.service;

import com.anterka.closeauthbackend.common.exception.InvalidUserStateTransitionException;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exhaustive test of the user transition matrix (all 4×4 pairs vs. the allowed-set). */
class UserStateMachineTest {

    private final UserStateMachine stateMachine = new UserStateMachine();

    private static final Set<Map.Entry<UserStatus, UserStatus>> ALLOWED = Set.of(
            Map.entry(UserStatus.PENDING, UserStatus.ACTIVE),
            Map.entry(UserStatus.PENDING, UserStatus.DELETED),
            Map.entry(UserStatus.ACTIVE, UserStatus.SUSPENDED),
            Map.entry(UserStatus.ACTIVE, UserStatus.DELETED),
            Map.entry(UserStatus.SUSPENDED, UserStatus.ACTIVE),
            Map.entry(UserStatus.SUSPENDED, UserStatus.DELETED)
    );

    @Test
    void everyTransitionPairMatchesTheAllowedMatrix() {
        for (UserStatus from : UserStatus.values()) {
            for (UserStatus to : UserStatus.values()) {
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
                            .isInstanceOf(InvalidUserStateTransitionException.class);
                }
            }
        }
    }

    @Test
    void deletedIsTerminal() {
        for (UserStatus to : UserStatus.values()) {
            assertThat(stateMachine.isAllowed(UserStatus.DELETED, to)).isFalse();
        }
    }
}

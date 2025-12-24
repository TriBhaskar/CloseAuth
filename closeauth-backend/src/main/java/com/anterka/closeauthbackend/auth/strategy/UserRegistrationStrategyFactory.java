package com.anterka.closeauthbackend.auth.strategy;


import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRegistrationStrategyFactory {

    private final ClientUserRegistrationStrategy clientUserRegistrationStrategy;
    // private final AnotherUserRegistrationStrategy anotherUserRegistrationStrategy; // Add other strategies as needed

    public UserRegistrationStrategy getStrategy(GlobalRoleEnum role) {
        return switch (role) {
            case END_USER -> clientUserRegistrationStrategy;
            // case "ANOTHER_ROLE" -> anotherUserRegistrationStrategy; // Add other strategies as needed
            default -> throw new IllegalArgumentException("Unknown user role: " + role);
        };
    }
}

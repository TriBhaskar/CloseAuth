package com.anterka.closeauthbackend.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for client-specific user registration.
 * The clientId is extracted from the JWT token, not provided by the user.
 */
public record ClientUserRegistrationDto(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 100, message = "Username must be between 3 and 100 characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank(message = "First name is required")
        String firstName,

        @NotBlank(message = "Last name is required")
        String lastName,

        String phone
) {
    /**
     * Convert to legacy UserRegistrationDto for compatibility
     */
    public UserRegistrationDto toUserRegistrationDto() {
        return new UserRegistrationDto(username, email, password, firstName, lastName, phone);
    }
}


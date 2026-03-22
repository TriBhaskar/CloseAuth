package com.anterka.closeauthbackend.auth.dto.response;

import java.time.LocalDateTime;

public record UserRegistrationResponse(
        Long userId,
        String email,
        String firstName,
        String lastName,
        String message,
        Long otpValiditySeconds,
        LocalDateTime timestamp
) {

    public static UserRegistrationResponse success(Long userId, String email, String firstName, String lastName, long otpValiditySeconds) {
        return new UserRegistrationResponse(
                userId,
                email,
                firstName,
                lastName,
                "Registration successful. Please verify your email to activate your account.",
                otpValiditySeconds,
                LocalDateTime.now()
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long userId;
        private String email;
        private String firstName;
        private String lastName;
        private String message;
        private Long otpValiditySeconds;
        private LocalDateTime timestamp = LocalDateTime.now();

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder otpValiditySeconds(Long otpValiditySeconds) {
            this.otpValiditySeconds = otpValiditySeconds;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public UserRegistrationResponse build() {
            return new UserRegistrationResponse(userId, email, firstName, lastName, message, otpValiditySeconds, timestamp);
        }
    }
}

package com.anterka.closeauthbackend.user.service;

import com.anterka.closeauthbackend.cache.repository.PasswordResetTokenRepository;
import com.anterka.closeauthbackend.common.exception.*;
import com.anterka.closeauthbackend.auth.dto.request.UserForgotPasswordDto;
import com.anterka.closeauthbackend.auth.dto.request.UserResetPasswordDto;
import com.anterka.closeauthbackend.auth.dto.response.UserTokenValidationResponse;
import com.anterka.closeauthbackend.notification.service.EmailService;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for handling password reset operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserPasswordResetService {

    private static final long TOKEN_EXPIRY_MINUTES = 10L;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public void processForgotPassword(UserForgotPasswordDto request) {
        String email = request.email();
        log.info("Processing forgot password request for email: {}", email);

        Users user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(
                        String.format("No user found with the requested email: [%s]", email)));

        String token = UUID.randomUUID().toString();
        long ttlSeconds = TOKEN_EXPIRY_MINUTES * 60;

        // Save token using repository
        tokenRepository.saveToken(token, user.getId().toString(), ttlSeconds);

        // Create password reset link and send email
        String resetLink = request.forgotPasswordLink() + "?token=" + token;
        sendEmail(user.getEmail(), resetLink, TOKEN_EXPIRY_MINUTES);

        log.info("Password reset token created for user: {}", email);
    }

    public void resetPassword(UserResetPasswordDto request) {
        // Validate password strength first
        validatePasswordStrength(request.newPassword());

        // Get user ID from token
        String userId = tokenRepository.getUserIdByToken(request.token())
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired token"));

        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new PasswordMismatchedException("Passwords entered do not match");
        }

        Users user = userRepository.findById(Integer.valueOf(userId))
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new PasswordReusedException("New password must be different from the current password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Invalidate the token after successful reset
        tokenRepository.invalidateToken(request.token());

        log.info("Password reset successful for user ID: {}", userId);
    }

    public UserTokenValidationResponse validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return new UserTokenValidationResponse(false, "Token is required");
        }

        if (tokenRepository.isTokenValid(token)) {
            return new UserTokenValidationResponse(true, "Token is valid");
        }

        return new UserTokenValidationResponse(false, "Invalid or expired token");
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new WeakPasswordException("Password must be at least 8 characters long");
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }

        if (!(hasLetter && hasDigit && hasSpecial)) {
            throw new WeakPasswordException("Password must contain at least one letter, one number, and one special character");
        }
    }

    private void sendEmail(String email, String resetLink, long tokenExpiryMinutes) {
        try {
            emailService.sendForgotPasswordLinkMail(email, resetLink, tokenExpiryMinutes);
        } catch (MessagingException exception) {
            log.error("Exception occurred while sending forgot password email to: {}", email);
        }
    }
}

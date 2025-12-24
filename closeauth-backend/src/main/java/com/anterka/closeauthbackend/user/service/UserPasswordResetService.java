package com.anterka.closeauthbackend.user.service;

import com.anterka.closeauthbackend.common.exception.*;
import com.anterka.closeauthbackend.auth.dto.request.UserForgotPasswordDto;
import com.anterka.closeauthbackend.auth.dto.request.UserResetPasswordDto;
import com.anterka.closeauthbackend.auth.dto.response.UserTokenValidationResponse;
import com.anterka.closeauthbackend.notification.service.EmailService;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import jakarta.mail.MessagingException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class UserPasswordResetService {

    private static final String PASSWORD_RESET_PREFIX = "password_reset:";
    private final JedisPooled jedisPooled;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public void processForgotPassword(UserForgotPasswordDto request) {
        try {
            // Find user by email
            String email = request.email();
            log.info("Processing the forgot password request");
            long tokenExpiryMinutes = 10L;
            String resetPasswordUrl = request.forgotPasswordLink();
            Users user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new UserNotFoundException(String.format("No user found with the requested email : [%s]", email)));

            String token = UUID.randomUUID().toString();

            String redisKey = PASSWORD_RESET_PREFIX + token;
            // Convert minutes to seconds for Jedis setex
            jedisPooled.setex(redisKey, (int) (tokenExpiryMinutes * 60), user.getId().toString());

            // Create password reset link
            String resetLink = resetPasswordUrl + "?token=" + token;
            sendEmail(user.getEmail(), resetLink, tokenExpiryMinutes);
        } catch (Exception e) {
            log.error("Error while processing forgot password request: {}", e.getMessage(), e);
            throw e;
        }
    }

    public void resetPassword(UserResetPasswordDto request) {
        try {
            String redisKey = PASSWORD_RESET_PREFIX + request.token();
            String userId = jedisPooled.get(redisKey);

            if (userId == null) {
                validatePasswordStrength(request.newPassword());
                throw new InvalidTokenException("Invalid or expired token");
            }

            if (!request.newPassword().equals(request.confirmPassword())) {
                throw new PasswordMismatchedException("Passwords entered do not match");
            }

            validatePasswordStrength(request.newPassword());

            Users user = userRepository.findById(Integer.valueOf(userId))
                    .orElseThrow(() -> new UserNotFoundException("User not found"));

            if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
                throw new PasswordReusedException("New password must be different from the current password");
            }

            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            userRepository.save(user);

            jedisPooled.del(redisKey);
        } catch (Exception e) {
            if (e instanceof InvalidTokenException || e instanceof PasswordMismatchedException ||
                    e instanceof PasswordReusedException || e instanceof UserNotFoundException ||
                    e instanceof WeakPasswordException) {
                throw e;
            }
            log.error("Error in resetPassword: {}", e.getMessage(), e);
            throw e;
        }
    }

    public UserTokenValidationResponse validateToken(String token) {
        try {
            if (token == null || token.isEmpty()) {
                return new UserTokenValidationResponse(false, "Token is required");
            }

            String redisKey = PASSWORD_RESET_PREFIX + token;
            String userId = jedisPooled.get(redisKey);

            if (userId == null) {
                return new UserTokenValidationResponse(false, "Invalid or expired token");
            }
            //TODO: Add the token validation rate limiting logic w.r.t an IP or user
            return new UserTokenValidationResponse(true, "Token is valid");
        } catch (Exception e) {
            log.error("Error in validateToken: {}", e.getMessage(), e);
            // Fail closed for security
            return new UserTokenValidationResponse(false, "Error validating token");
        }
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new WeakPasswordException("Password must be at least 8 characters long");
        }

        // Check for password complexity
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

    private void sendEmail(String email, String resetLink, long tokenExpiryMinutes){
        try {
            emailService.sendForgotPasswordLinkMail(email, resetLink, tokenExpiryMinutes);
        } catch (MessagingException exception) {
            log.error(String.format("Exception occurred while sending the forgot password email to the user : [%s]", email));
        }
    }
}

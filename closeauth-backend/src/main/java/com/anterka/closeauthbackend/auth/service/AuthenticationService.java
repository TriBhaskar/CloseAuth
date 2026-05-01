package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.dto.request.UserEmailVerificationDto;
import com.anterka.closeauthbackend.auth.dto.request.UserLoginDto;
import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.auth.dto.request.UserResendOtpDto;
import com.anterka.closeauthbackend.auth.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserLoginResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.auth.strategy.UserRegistrationStrategy;
import com.anterka.closeauthbackend.auth.strategy.UserRegistrationStrategyFactory;
import com.anterka.closeauthbackend.cache.service.RateLimiterService;
import com.anterka.closeauthbackend.cache.service.RegistrationCacheService;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.common.exception.DataAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.UserAuthenticationException;
import com.anterka.closeauthbackend.common.exception.UserNotFoundException;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.notification.service.EmailService;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.user.repository.GlobalRolesRepository;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {
    private final UserRepository userRepository;
    private final GlobalRolesRepository globalRolesRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final EmailService emailService;
    private final RegistrationCacheService registrationCacheService;
    private final UserRegistrationStrategyFactory registrationStrategyFactory;
    private final JwtTokenService jwtTokenService;
    private final RateLimiterService rateLimiterService;
    private final CloseAuthProperties properties;

    public UserRegistrationResponse registerUser(UserRegistrationDto request) {

        validateUserData(request);

        // Generate OTP for email verification
        String otp = otpService.generateOtp();
        long otpValiditySeconds = otpService.saveOtp(request.email(), otp);

        // Send email asynchronously
        sendOtpEmail(request.email(), otp);

        RegistrationData registrationData = new RegistrationData(
                request,
                GlobalRoleEnum.END_USER
        );

        registrationCacheService.saveRegistration(request.email(), registrationData);

        return UserRegistrationResponse.success(
                null,
                request.email(),
                request.firstName(),
                request.lastName(),
                otpValiditySeconds
        );
    }

    private void validateUserData(UserRegistrationDto userRegistrationDto) {
        if (userRepository.existsByUsername(userRegistrationDto.username())) {
            throw new DataAlreadyExistsException("Registration failed. Please check your details.");
        }
        if (userRepository.existsByEmail(userRegistrationDto.email())) {
            throw new DataAlreadyExistsException("Registration failed. Please check your details.");
        }
        if (userRepository.existsByPhone(userRegistrationDto.phone())) {
            throw new DataAlreadyExistsException("Registration failed. Please check your details.");
        }
    }

    @Transactional
    public CustomApiResponse<Void> verifyUserEmail(UserEmailVerificationDto request) {
        log.info("Verifying user email: {}", request.email());

        registrationCacheService.getRegistration(request.email())
                .ifPresentOrElse(registrationData -> {
                    if (!otpService.validateOtp(request.email(), request.verificationCode())) {
                        throw new UserRegistrationException("Invalid OTP for email: " + request.email());
                    }

                    UserRegistrationStrategy strategy = registrationStrategyFactory
                            .getStrategy(registrationData.globalRoleEnum());

                    Users user = strategy.createUser(registrationData.registrationDto());
                    user.setEmailVerified(true);
                    user = userRepository.save(user);

                    strategy.performPostRegistrationSetup(user, registrationData.registrationDto());

                    registrationCacheService.deleteRegistration(request.email());
                    otpService.deleteOtp(request.email());

                    log.info("User email verified successfully: {}", request.email());
                }, () -> {
                    throw new UserRegistrationException("No registration found for email: " + request.email());
                });

        return CustomApiResponse.success("User registered successfully");
    }

    public ResendOtpResponse resendOtp(UserResendOtpDto request) {
        // Rate limit resend OTP requests
        if (rateLimiterService.isLimited("resend_otp", request.email())) {
            throw new UserRegistrationException("Too many OTP requests. Please try again later.");
        }

        if (registrationCacheService.registrationExists(request.email())) {
            String otp = otpService.generateOtp();
            otpService.saveOtp(request.email(), otp);
            sendOtpEmail(request.email(), otp);
        } else {
            throw new UserRegistrationException("No registration found for email: " + request.email());
        }

        return new ResendOtpResponse(
                "OTP resent successfully. Please verify your email to activate your account.",
                otpService.getOtpValiditySeconds(),
                request.email(),
                LocalDateTime.now()
        );
    }

    @Transactional
    public UserLoginResponse loginUser(UserLoginDto request, String clientId) {
        log.info("Processing login request for email: {}", request.email());

        Users user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException("Invalid email or password"));

        // Check if account is locked
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new UserAuthenticationException("Account is temporarily locked. Please try again later.");
        }

        // Validate password
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            handleFailedLogin(user);
            throw new UserAuthenticationException("Invalid email or password");
        }

        // Check if user is active and verified
        if (!user.getEmailVerified()) {
            throw new UserAuthenticationException("Email not verified. Please verify your email to login.");
        }
        if (user.isDisabled()) {
            throw new UserAuthenticationException("Account is disabled. Please contact support.");
        }
        if (user.isLocked()) {
            throw new UserAuthenticationException("Account is locked. Please contact support.");
        }

        // Successful login — reset failed attempts
        resetFailedAttempts(user);

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // Generate JWT token
        String accessToken = jwtTokenService.generateToken(user, clientId);
        LocalDateTime tokenExpiresAt = LocalDateTime.ofInstant(
                jwtTokenService.getTokenExpiration(clientId),
                ZoneId.systemDefault()
        );

        log.info("User logged in successfully: {}", request.email());

        return UserLoginResponse.success(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                accessToken,
                tokenExpiresAt
        );
    }

    /**
     * Handle failed login attempt — increment counter, lock if threshold exceeded.
     */
    private void handleFailedLogin(Users user) {
        int attempts = (user.getFailedAttempts() != null ? user.getFailedAttempts() : 0) + 1;
        user.setFailedAttempts(attempts);

        int maxAttempts = properties.getSecurity().getMaxLoginAttempts();
        if (attempts >= maxAttempts) {
            int lockoutMinutes = properties.getSecurity().getLockoutDurationMinutes();
            user.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutMinutes));
            log.warn("Account locked for user {} after {} failed attempts", user.getEmail(), attempts);
        }

        userRepository.save(user);
    }

    /**
     * Reset failed login attempts on successful login.
     */
    private void resetFailedAttempts(Users user) {
        if (user.getFailedAttempts() != null && user.getFailedAttempts() > 0) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
        }
    }

    /**
     * Send OTP email asynchronously with error logging.
     */
    private void sendOtpEmail(String email, String otp) {
        emailService.sendOTPMail(email, otp)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        log.error("Unexpected error sending OTP email to {}: {}", email, throwable.getMessage(), throwable);
                    } else if (!success) {
                        log.warn("Failed to send OTP email to {}, may need retry", email);
                    } else {
                        log.debug("OTP email queued successfully for {}", email);
                    }
                });
    }
}

package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.common.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.auth.dto.request.UserEmailVerificationDto;
import com.anterka.closeauthbackend.auth.dto.request.UserLoginDto;
import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.auth.dto.request.UserResendOtpDto;
import com.anterka.closeauthbackend.auth.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserLoginResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.common.exception.DataAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.UserAuthenticationException;
import com.anterka.closeauthbackend.common.exception.UserNotFoundException;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.user.repository.GlobalRolesRepository;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import com.anterka.closeauthbackend.notification.service.EmailService;
import com.anterka.closeauthbackend.cache.service.RegistrationCacheService;
import com.anterka.closeauthbackend.auth.strategy.UserRegistrationStrategy;
import com.anterka.closeauthbackend.auth.strategy.UserRegistrationStrategyFactory;
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

    public UserRegistrationResponse registerUser(UserRegistrationDto request){

        validateUserData(request);

        // Generate OTP for email verification
        String otp = otpService.generateOtp();
        long otpValiditySeconds = otpService.saveOtp(request.email(), otp);

        // Send email asynchronously with proper error handling
        emailService.sendOTPMail(request.email(), otp)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        log.error("Unexpected error sending OTP email to {}: {}",
                                request.email(), throwable.getMessage(), throwable);
                        // Optional: Save to dead letter queue for retry
                        // Optional: Send alert to monitoring system
                    } else if (!success) {
                        log.warn("Failed to send OTP email to {}, may need retry", request.email());
                        // Optional: Trigger retry mechanism
                    } else {
                        log.info("OTP email queued successfully for {}", request.email());
                    }
                });


        RegistrationData registrationData = new RegistrationData(
                request,
                GlobalRoleEnum.END_USER // Default role
        );

        registrationCacheService.saveRegistration(request.email(), registrationData);

        return UserRegistrationResponse.success(
                null, // userId will be set after email verification
                request.email(),
                request.firstName(),
                request.lastName(),
                otpValiditySeconds
        );
    }

    private void validateUserData(UserRegistrationDto userRegistrationDto) {

        if(userRepository.existsByUsername(userRegistrationDto.username())) {
            throw new DataAlreadyExistsException("Username already exists: " + userRegistrationDto.username());
        }

        if (userRepository.existsByEmail(userRegistrationDto.email())) {
            throw new DataAlreadyExistsException("Email already exists: " + userRegistrationDto.email());
        }

        if (userRepository.existsByPhone(userRegistrationDto.phone())) {
            throw new DataAlreadyExistsException("Phone number already exists: " + userRegistrationDto.phone());
        }
    }

    @Transactional
    public CustomApiResponse verifyUserEmail(UserEmailVerificationDto request) {
        log.info("Verifying user email: {}", request.email());

        registrationCacheService.getRegistration(request.email())
                .ifPresentOrElse(registrationData -> {
                    String cachedOtp = otpService.getOtp(request.email());
                    if (cachedOtp == null || !cachedOtp.equals(request.verificationCode())) {
                        throw new UserRegistrationException("Invalid OTP for email: " + request.email());
                    } else {
                        // Get the appropriate strategy
                        UserRegistrationStrategy strategy = registrationStrategyFactory
                                .getStrategy(registrationData.globalRoleEnum());

                        // Create user using strategy
                        Users user = strategy.createUser(registrationData.registrationDto());
                        user.setEmailVerified(true);

                        // Save user to database
                        user = userRepository.save(user);

                        // Perform post-registration setup (create profiles, etc.)
                        strategy.performPostRegistrationSetup(user, registrationData.registrationDto());

                        // Clean up cache
                        registrationCacheService.deleteRegistration(request.email());
                        otpService.deleteOtp(request.email());

                        log.info("User email verified successfully: {}", request.email());
                    }
                }, () -> {
                    throw new UserRegistrationException("No registration found for email: " + request.email());
                });

        return CustomApiResponse.builder()
                .status(ResponseStatusEnum.SUCCESS)
                .message("User registered successfully")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Transactional
    public ResendOtpResponse resendOtp(UserResendOtpDto request) {
        if (registrationCacheService.registrationExists(request.email())) {
            String otp = otpService.generateOtp();
            otpService.saveOtp(request.email(), otp);

        // Send email asynchronously with proper error handling
            emailService.sendOTPMail(request.email(), otp)
                    .whenComplete((success, throwable) -> {
                        if (throwable != null) {
                            log.error("Unexpected error sending OTP email to {}: {}",
                                    request.email(), throwable.getMessage(), throwable);
                            // Optional: Save to dead letter queue for retry
                            // Optional: Send alert to monitoring system
                        } else if (!success) {
                            log.warn("Failed to send OTP email to {}, may need retry", request.email());
                            // Optional: Trigger retry mechanism
                        } else {
                            log.info("OTP email queued successfully for {}", request.email());
                        }
                    });

        } else {
            throw new UserRegistrationException("No registration found for email: " + request.email());
        }

    return new ResendOtpResponse(
                "OTP resent successfully please verify your email to activate your account",
                OtpService.OTP_VALIDITY_SECONDS,
                request.email(),
                LocalDateTime.now()
        );
    }

    @Transactional
    public UserLoginResponse loginUser(UserLoginDto request, String clientId) {
        log.info("Processing login request for email: {} with clientId: {}", request.email(), clientId);

        // Find user by email
        Users user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException("User not found with email: " + request.email()));

        // Validate password
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Invalid password attempt for email: {}", request.email());
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

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // Generate JWT token using the client ID from the bearer token
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
}

package com.anterka.closeauthbackend.service;

import com.anterka.closeauthbackend.dto.CustomApiResponse;
import com.anterka.closeauthbackend.dto.RegistrationData;
import com.anterka.closeauthbackend.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.dto.request.UserEmailVerificationDto;
import com.anterka.closeauthbackend.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.dto.request.UserResendOtpDto;
import com.anterka.closeauthbackend.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.entities.Users;
import com.anterka.closeauthbackend.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.exception.DataAlreadyExistsException;
import com.anterka.closeauthbackend.exception.UserRegistrationException;
import com.anterka.closeauthbackend.repository.GlobalRolesRepository;
import com.anterka.closeauthbackend.repository.UserRepository;
import com.anterka.closeauthbackend.service.cache.RegistrationCacheService;
import com.anterka.closeauthbackend.service.strategy.UserRegistrationStrategy;
import com.anterka.closeauthbackend.service.strategy.UserRegistrationStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
}

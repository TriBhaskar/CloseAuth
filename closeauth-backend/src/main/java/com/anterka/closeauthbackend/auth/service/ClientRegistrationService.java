package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.dto.request.ClientUserRegistrationDto;
import com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.auth.enums.VerificationMode;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.auth.strategy.verification.VerificationStrategy;
import com.anterka.closeauthbackend.auth.strategy.verification.VerificationStrategyFactory;
import com.anterka.closeauthbackend.cache.service.RegistrationCacheService;
import com.anterka.closeauthbackend.client.entity.ApplicationRegistrationConfig;
import com.anterka.closeauthbackend.client.repository.ApplicationRegistrationConfigRepository;
import com.anterka.closeauthbackend.common.exception.DataAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Service for handling client-specific user registration.
 * Dynamically adapts registration flow based on client's verification configuration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientRegistrationService {

    private final UserRepository userRepository;
    private final ApplicationRegistrationConfigRepository configRepository;
    private final VerificationStrategyFactory verificationStrategyFactory;
    private final RegistrationCacheService registrationCacheService;
    private final RegistrationCompletionService registrationCompletionService;

    /**
     * Registers a user for a specific client application.
     * The registration flow is determined by the client's verification configuration.
     *
     * @param clientId The OAuth client ID (extracted from JWT)
     * @param request The registration request data
     * @return UserRegistrationResponse with registration status and next steps
     */
    public UserRegistrationResponse registerUser(String clientId, ClientUserRegistrationDto request) {
        log.info("Processing client-specific registration: clientId={}, email={}", clientId, request.email());

        // Load client registration configuration
        ApplicationRegistrationConfig config = configRepository.findByClient_ClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Registration config not found for client: " + clientId));

        // Validate registration is enabled
        if (!config.getRegistrationEnabled()) {
            throw new UserRegistrationException("Registration is currently disabled for this application");
        }

        if (!config.getAllowSelfRegistration()) {
            throw new UserRegistrationException("Self-registration is not allowed for this application");
        }

        // Validate required fields based on config
        validateRegistrationData(request, config);

        // Validate user doesn't already exist
        validateUserDoesNotExist(request);

        // Check for existing pending registration
        if (registrationCacheService.registrationExists(request.email())) {
            throw new DataAlreadyExistsException("A pending registration already exists for this email. Please verify or wait for it to expire.");
        }

        // Determine verification mode and strategy
        VerificationMode verificationMode = VerificationMode.valueOf(config.getVerificationMethod());
        VerificationStrategy strategy = verificationStrategyFactory.getStrategy(verificationMode);

        // Build pending verifications set
        Set<VerificationType> pendingVerifications = new HashSet<>(strategy.getRequiredVerificationTypes());

        // Create registration data
        RegistrationData registrationData = new RegistrationData(
                request.toUserRegistrationDto(),
                GlobalRoleEnum.END_USER,
                clientId,
                verificationMode,
                pendingVerifications
        );

        // Handle AUTO_APPROVE - persist immediately
        if (strategy.requiresImmediatePersistence()) {
            registrationCompletionService.persistUserImmediately(registrationData);

            return UserRegistrationResponse.builder()
                    .message("Registration successful! Your account is now active.")
                    .email(request.email())
                    .firstName(request.firstName())
                    .lastName(request.lastName())
                    .build();
        }

        // Save to registration cache
        registrationCacheService.saveRegistration(request.email(), registrationData);

        // Initiate verification process (send OTP, queue for admin, etc.)
        strategy.initiate(registrationData);

        // Build response based on verification mode
        return buildRegistrationResponse(request, verificationMode, OtpService.OTP_VALIDITY_SECONDS);
    }

    /**
     * Validates registration data against client configuration.
     */
    private void validateRegistrationData(ClientUserRegistrationDto request, ApplicationRegistrationConfig config) {
        if (config.getRequireFirstName() && (request.firstName() == null || request.firstName().isBlank())) {
            throw new UserRegistrationException("First name is required");
        }

        if (config.getRequireLastName() && (request.lastName() == null || request.lastName().isBlank())) {
            throw new UserRegistrationException("Last name is required");
        }

        VerificationMode mode = VerificationMode.valueOf(config.getVerificationMethod());

        // Phone is required for PHONE and EMAIL_AND_PHONE modes
        if ((mode == VerificationMode.PHONE || mode == VerificationMode.EMAIL_AND_PHONE)
                && (request.phone() == null || request.phone().isBlank())) {
            throw new UserRegistrationException("Phone number is required for this registration mode");
        }

        // Phone is required if explicitly configured
        if (config.getRequirePhone() && (request.phone() == null || request.phone().isBlank())) {
            throw new UserRegistrationException("Phone number is required");
        }
    }

    /**
     * Validates that the user doesn't already exist in the database.
     */
    private void validateUserDoesNotExist(ClientUserRegistrationDto request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DataAlreadyExistsException("Username already exists: " + request.username());
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new DataAlreadyExistsException("Email already exists: " + request.email());
        }

        if (request.phone() != null && !request.phone().isBlank() && userRepository.existsByPhone(request.phone())) {
            throw new DataAlreadyExistsException("Phone number already exists: " + request.phone());
        }
    }

    /**
     * Builds the registration response based on verification mode.
     */
    private UserRegistrationResponse buildRegistrationResponse(ClientUserRegistrationDto request,
                                                               VerificationMode mode,
                                                               long otpValiditySeconds) {
        String message = switch (mode) {
            case EMAIL -> "Registration initiated. Please check your email for the verification code.";
            case PHONE -> "Registration initiated. Please check your phone for the verification code.";
            case EMAIL_AND_PHONE -> "Registration initiated. Please verify both your email and phone number.";
            case ADMIN_APPROVAL -> "Registration submitted. Your account is pending admin approval.";
            case AUTO_APPROVE -> "Registration successful! Your account is now active.";
        };

        return UserRegistrationResponse.builder()
                .message(message)
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .otpValiditySeconds(mode != VerificationMode.ADMIN_APPROVAL ? otpValiditySeconds : null)
                .build();
    }
}


package com.anterka.closeauthbackend.oauth2.controller;

import com.anterka.closeauthbackend.auth.dto.request.ClientUserRegistrationDto;
import com.anterka.closeauthbackend.auth.dto.request.PhoneVerificationDto;
import com.anterka.closeauthbackend.auth.dto.request.ResendPhoneOtpDto;
import com.anterka.closeauthbackend.auth.dto.request.UserEmailVerificationDto;
import com.anterka.closeauthbackend.auth.dto.request.UserResendOtpDto;
import com.anterka.closeauthbackend.auth.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.auth.service.OtpService;
import com.anterka.closeauthbackend.auth.service.RegistrationCompletionService;
import com.anterka.closeauthbackend.cache.service.RateLimiterService;
import com.anterka.closeauthbackend.common.constants.ApiPaths;
import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.common.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.oauth2.service.OAuth2RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Controller for OAuth2 Authorization Code Flow user registration.
 * Handles user registration when users are redirected to the auth server
 * and don't have an account for the requesting client application.
 *
 * This is separate from the Admin Auth flow (ApiPaths.ADMIN_BASE).
 */
@RestController
@RequestMapping(ApiPaths.USER_REGISTER_URL)
@RequiredArgsConstructor
@Slf4j
public class OAuth2RegistrationController {

    private final OAuth2RegistrationService registrationService;
    private final RegistrationCompletionService registrationCompletionService;
    private final OtpService otpService;
    private final RateLimiterService rateLimiter;

    /**
     * Register a new user for a specific OAuth2 client.
     * Called during authorization code flow when user doesn't have an account.
     *
     * @param clientId The OAuth2 client ID from the authorization request
     * @param registrationDto User registration details
     */
    @PostMapping(value = "/{clientId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<UserRegistrationResponse> registerUser(
            @PathVariable String clientId,
            @Valid @RequestBody ClientUserRegistrationDto registrationDto,
            HttpServletRequest servletRequest) {

        log.info("OAuth2 registration request for client: {}, email: {}", clientId, registrationDto.email());

        // Rate limiting for registration
        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("oauth2_register", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(UserRegistrationResponse.builder()
                            .message("Too many registration attempts. Please try again later.")
                            .build());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(registrationService.registerUser(clientId, registrationDto));
    }

    /**
     * Verify email OTP for OAuth2 registration.
     */
    @PostMapping(value = "/verify-email", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> verifyEmail(
            @Valid @RequestBody UserEmailVerificationDto verificationRequest,
            HttpServletRequest servletRequest) {

        log.info("OAuth2 email verification for: {}", verificationRequest.email());

        // Rate limiting
        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("otp_verify", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(CustomApiResponse.<Void>builder()
                            .message("Too many verification attempts. Please try again later.")
                            .status(ResponseStatusEnum.FAILED)
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        // Validate OTP
        if (!otpService.validateOtp(verificationRequest.email(), verificationRequest.verificationCode())) {
            return ResponseEntity.badRequest()
                    .body(CustomApiResponse.<Void>builder()
                            .message("Invalid or expired verification code")
                            .status(ResponseStatusEnum.FAILED)
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        // Complete email verification
        boolean registrationComplete = registrationCompletionService.completeVerification(
                verificationRequest.email(), VerificationType.EMAIL);

        String message = registrationComplete
                ? "Email verified successfully. Your account is now active."
                : "Email verified successfully. Please complete remaining verification steps.";

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .message(message)
                .status(ResponseStatusEnum.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build());
    }

    /**
     * Verify phone OTP for OAuth2 registration.
     */
    @PostMapping(value = "/verify-phone", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> verifyPhone(
            @Valid @RequestBody PhoneVerificationDto verificationRequest,
            HttpServletRequest servletRequest) {

        log.info("OAuth2 phone verification for: {}", verificationRequest.phone());

        // Rate limiting (shared with email OTP)
        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("otp_verify", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(CustomApiResponse.<Void>builder()
                            .message("Too many verification attempts. Please try again later.")
                            .status(ResponseStatusEnum.FAILED)
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        // Validate phone OTP
        if (!otpService.validatePhoneOtp(verificationRequest.phone(), verificationRequest.otp())) {
            return ResponseEntity.badRequest()
                    .body(CustomApiResponse.<Void>builder()
                            .message("Invalid or expired verification code")
                            .status(ResponseStatusEnum.FAILED)
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        // Complete phone verification
        boolean registrationComplete = registrationCompletionService.completeVerification(
                verificationRequest.email(), VerificationType.PHONE);

        String message = registrationComplete
                ? "Phone verified successfully. Your account is now active."
                : "Phone verified successfully. Please complete remaining verification steps.";

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .message(message)
                .status(ResponseStatusEnum.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build());
    }

    /**
     * Resend email OTP for OAuth2 registration.
     */
    @PostMapping(value = "/resend-email-otp", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<ResendOtpResponse> resendEmailOtp(
            @Valid @RequestBody UserResendOtpDto request,
            HttpServletRequest servletRequest) {

        log.info("OAuth2 resend email OTP for: {}", request.email());

        // Rate limiting
        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("otp_resend", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ResendOtpResponse(
                            "Too many requests. Please try again later.",
                            0L,
                            request.email(),
                            LocalDateTime.now()
                    ));
        }

        return ResponseEntity.ok(registrationService.resendEmailOtp(request));
    }

    /**
     * Resend phone OTP for OAuth2 registration.
     */
    @PostMapping(value = "/resend-phone-otp", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<ResendOtpResponse> resendPhoneOtp(
            @Valid @RequestBody ResendPhoneOtpDto request,
            HttpServletRequest servletRequest) {

        log.info("OAuth2 resend phone OTP for: {}", request.phone());

        // Rate limiting (shared with email OTP)
        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("otp_resend", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ResendOtpResponse(
                            "Too many requests. Please try again later.",
                            0L,
                            request.email(),
                            LocalDateTime.now()
                    ));
        }

        return ResponseEntity.ok(registrationService.resendPhoneOtp(request));
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}


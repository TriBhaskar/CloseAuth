package com.anterka.closeauthbackend.auth.controller;


import com.anterka.closeauthbackend.auth.dto.request.*;
import com.anterka.closeauthbackend.common.constants.ApiPaths;
import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.common.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.auth.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserLoginResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.auth.service.AuthenticationService;
import com.anterka.closeauthbackend.user.service.UserPasswordResetService;
import com.anterka.closeauthbackend.cache.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping(ApiPaths.ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationService authenticationService;
    private final RateLimiterService rateLimiter;
    private final UserPasswordResetService passwordResetService;

    @PostMapping(value = ApiPaths.LOGIN, consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<UserLoginResponse> loginUser(
            @RequestBody UserLoginDto userLoginDto,
            Authentication authentication) {
        log.info("Received authenticated user login request for email: {}", userLoginDto.email());

        String clientId = extractClientIdFromAuthentication(authentication);

        return ResponseEntity.ok(authenticationService.loginUser(userLoginDto, clientId));
    }

    private String extractClientIdFromAuthentication(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }

    @PostMapping(value = ApiPaths.REGISTER, consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<UserRegistrationResponse> registerUser(@RequestBody UserRegistrationDto userRegistrationDto) {
        log.info("Received user creation request for username: {}", userRegistrationDto.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(authenticationService.registerUser(userRegistrationDto));
    }

    @PostMapping(value = ApiPaths.VERIFY_EMAIL, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse<Void>> verifyEmail(@Valid @RequestBody UserEmailVerificationDto userEmailVerificationRequest) {
        log.info("Received OTP verification request for email: {}", userEmailVerificationRequest.email());
        return ResponseEntity.ok(authenticationService.verifyUserEmail(userEmailVerificationRequest));
    }

    @PostMapping(value = ApiPaths.RESEND_OTP, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ResendOtpResponse> resendOTP(@Valid @RequestBody UserResendOtpDto userResendOtpRequest) {
        return ResponseEntity.ok(authenticationService.resendOtp(userResendOtpRequest));
    }

    @PostMapping(value = ApiPaths.FORGOT_PASSWORD, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse<Void>> forgotPassword(
            @Valid @RequestBody UserForgotPasswordDto userForgotPasswordRequest,
            HttpServletRequest servletRequest) {

        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("forgot_password", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(CustomApiResponse.<Void>builder()
                            .message("Too many requests. Please try again later.")
                            .status(ResponseStatusEnum.FAILED)
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        passwordResetService.processForgotPassword(userForgotPasswordRequest);

        // Always return same message to prevent email enumeration
        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .message("If your email is registered, you will receive a password reset link shortly")
                .status(ResponseStatusEnum.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @PostMapping(value = ApiPaths.RESET_PASSWORD, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse<Void>> resetPassword(
            @RequestBody UserResetPasswordDto request,
            HttpServletRequest servletRequest) {

        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("reset_password", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(CustomApiResponse.<Void>builder()
                            .message("Too many attempts. Please try again later.")
                            .status(ResponseStatusEnum.FAILED)
                            .timestamp(LocalDateTime.now())
                            .build());
        }

        passwordResetService.resetPassword(request);

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .message("Password reset successful")
                .status(ResponseStatusEnum.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build());
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

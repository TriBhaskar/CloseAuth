package com.anterka.closeauthbackend.auth.controller;


import com.anterka.closeauthbackend.auth.dto.request.*;
import com.anterka.closeauthbackend.auth.dto.response.UserTokenValidationResponse;
import com.anterka.closeauthbackend.common.constants.ApiPaths;
import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.common.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.auth.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserLoginResponse;
import com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.auth.service.AuthenticationService;
import com.anterka.closeauthbackend.user.service.UserPasswordResetService;
import com.anterka.closeauthbackend.cache.service.RateLimiterService;
import com.anterka.closeauthbackend.cache.strategy.LoginRateLimitStrategy;
import com.anterka.closeauthbackend.cache.strategy.VerifyOtpRateLimitStrategy;
import com.anterka.closeauthbackend.common.exception.RateLimitExceededException;
import com.anterka.closeauthbackend.common.util.ClientIpResolver;
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
    private final ClientIpResolver clientIpResolver;

    @PostMapping(value = ApiPaths.LOGIN, consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<UserLoginResponse> loginUser(
            @Valid @RequestBody UserLoginDto userLoginDto,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        log.info("Received authenticated user login request for email: {}", userLoginDto.email());

        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited(LoginRateLimitStrategy.ACTION, clientIp)) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.");
        }

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
    public ResponseEntity<UserRegistrationResponse> registerUser(@Valid @RequestBody UserRegistrationDto userRegistrationDto) {
        log.info("Received user creation request for username: {}", userRegistrationDto.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(authenticationService.registerUser(userRegistrationDto));
    }

    @PostMapping(value = ApiPaths.VERIFY_EMAIL, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse<Void>> verifyEmail(@Valid @RequestBody UserEmailVerificationDto userEmailVerificationRequest) {
        log.info("Received OTP verification request for email: {}", userEmailVerificationRequest.email());

        // Limit OTP verification attempts per email to prevent brute-forcing the numeric code.
        if (rateLimiter.isLimited(VerifyOtpRateLimitStrategy.ACTION, userEmailVerificationRequest.email())) {
            throw new RateLimitExceededException("Too many verification attempts. Please try again later.");
        }

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
            @Valid @RequestBody UserResetPasswordDto request,
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

    @GetMapping(ApiPaths.VALIDATE_RESET_TOKEN)
    public ResponseEntity<UserTokenValidationResponse> validateResetToken(
            @RequestParam String token
    ){
        // Do NOT log the raw token — it is a sensitive, single-use credential.
        log.info("Received reset token validation request");
        UserTokenValidationResponse response = passwordResetService.validateToken(token);
        if(response.valid()){
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.badRequest().body(response);
    }

    private String getClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}

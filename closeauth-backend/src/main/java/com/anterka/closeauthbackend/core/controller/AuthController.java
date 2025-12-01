package com.anterka.closeauthbackend.core.controller;


import com.anterka.closeauthbackend.constants.ApiPaths;
import com.anterka.closeauthbackend.dto.CustomApiResponse;
import com.anterka.closeauthbackend.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.dto.request.*;
import com.anterka.closeauthbackend.dto.response.ResendOtpResponse;
import com.anterka.closeauthbackend.dto.response.UserLoginResponse;
import com.anterka.closeauthbackend.dto.response.UserRegistrationResponse;
import com.anterka.closeauthbackend.exception.InvalidTokenException;
import com.anterka.closeauthbackend.exception.PasswordMismatchedException;
import com.anterka.closeauthbackend.exception.PasswordReusedException;
import com.anterka.closeauthbackend.exception.WeakPasswordException;
import com.anterka.closeauthbackend.service.AuthenticationService;
import com.anterka.closeauthbackend.service.UserPasswordResetService;
import com.anterka.closeauthbackend.service.cache.RateLimiterService;
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
    // Protected endpoint for user login (requires OAuth2 access token with 'client.create' scope)

    @PostMapping(value = ApiPaths.LOGIN, consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<UserLoginResponse> loginUser(
            @RequestBody UserLoginDto userLoginDto,
            Authentication authentication) {
        log.info("Received authenticated user login request for email: {}", userLoginDto.email());

        // Extract client ID from the JWT token
        String clientId = extractClientIdFromAuthentication(authentication);

        if (clientId != null) {
            log.debug("Client ID extracted from bearer token: {}", clientId);
        }

        return ResponseEntity.ok(authenticationService.loginUser(userLoginDto, clientId));
    }

    /**
     * Extracts the client ID from the JWT token in the Authentication object.
     * The client ID is in the 'sub' claim of the JWT.
     *
     * @param authentication The Spring Security Authentication object
     * @return The client ID or null if not found
     */
    private String extractClientIdFromAuthentication(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            // The 'sub' claim contains the client ID
            String subject = jwt.getSubject();
            log.debug("Extracted subject (client ID) from JWT: {}", subject);
            return subject;
        }
        log.warn("Could not extract client ID from authentication - not a JWT token");
        return null;
    }
    @PostMapping(value = ApiPaths.REGISTER, consumes = {MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<UserRegistrationResponse> registerUser(@RequestBody UserRegistrationDto userRegistrationDto) {
        log.info("Received authenticated user creation request for username: {}", userRegistrationDto.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(authenticationService.registerUser(userRegistrationDto));
    }

    @PostMapping(value = ApiPaths.VERIFY_EMAIL, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse> verifyEmail(@Valid @RequestBody UserEmailVerificationDto userEmailVerificationRequest) {
        log.info("Received OTP verification request for email: {}", userEmailVerificationRequest.email());
        return ResponseEntity.ok(authenticationService.verifyUserEmail(userEmailVerificationRequest));
    }

    @PostMapping(value = ApiPaths.RESEND_OTP, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ResendOtpResponse> resendOTP(@Valid @RequestBody UserResendOtpDto userResendOtpRequest) {
        return ResponseEntity.ok(authenticationService.resendOtp(userResendOtpRequest));
    }

    @PostMapping(value = ApiPaths.FORGOT_PASSWORD, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse> forgotPassword(@Valid @RequestBody UserForgotPasswordDto userForgotPasswordRequest, HttpServletRequest servletRequest) {
        log.info("Received forgot password request for email: " + userForgotPasswordRequest.email());

        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("forgot_password", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new CustomApiResponse("Too many requests. Please try again later.", ResponseStatusEnum.FAILED, LocalDateTime.now()));
        }
        try {
            passwordResetService.processForgotPassword(userForgotPasswordRequest);
            return ResponseEntity.ok().body(new CustomApiResponse("If your email is registered, you will receive a password reset link shortly", ResponseStatusEnum.SUCCESS, LocalDateTime.now()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new CustomApiResponse("If your email is registered, you will receive a password reset link shortly",ResponseStatusEnum.FAILED,LocalDateTime.now()));
        }
    }

    @PostMapping(value = ApiPaths.RESET_PASSWORD, consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<CustomApiResponse> resetPassword(@RequestBody UserResetPasswordDto request,
                                                           HttpServletRequest servletRequest) {
        // Rate limiting for reset password attempts
        String clientIp = getClientIp(servletRequest);
        if (rateLimiter.isLimited("reset_password", clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new CustomApiResponse( "Too many attempts. Please try again later.",ResponseStatusEnum.FAILED,LocalDateTime.now()));
        }
        try {
            passwordResetService.resetPassword(request);
            return ResponseEntity.ok().body(new CustomApiResponse("Password reset successful",ResponseStatusEnum.SUCCESS,LocalDateTime.now()));
        } catch (InvalidTokenException | PasswordMismatchedException |
                 WeakPasswordException | PasswordReusedException e) {
            return ResponseEntity.badRequest().body(new CustomApiResponse( "Exception occurred while resetting password : "+ e.getMessage(),ResponseStatusEnum.FAILED,LocalDateTime.now()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new CustomApiResponse("An unexpected error occurred. Please try again.",ResponseStatusEnum.FAILED,LocalDateTime.now()));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

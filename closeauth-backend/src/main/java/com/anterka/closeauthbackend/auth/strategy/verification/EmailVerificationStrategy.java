package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.auth.service.OtpService;
import com.anterka.closeauthbackend.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Verification strategy for EMAIL mode.
 * Sends email OTP and requires email verification before activation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationStrategy implements VerificationStrategy {

    private final OtpService otpService;
    private final EmailService emailService;

    @Override
    public void initiate(RegistrationData registrationData) {
        String email = registrationData.registrationDto().email();
        String otp = otpService.generateOtp();
        otpService.saveOtp(email, otp);

        log.info("Initiating email verification for: {}", email);

        emailService.sendOTPMail(email, otp)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to send email OTP to {}: {}", email, throwable.getMessage());
                    } else if (!success) {
                        log.warn("Email OTP sending returned false for {}", email);
                    } else {
                        log.info("Email OTP sent successfully to {}", email);
                    }
                });
    }

    @Override
    public Set<VerificationType> getRequiredVerificationTypes() {
        return Set.of(VerificationType.EMAIL);
    }
}


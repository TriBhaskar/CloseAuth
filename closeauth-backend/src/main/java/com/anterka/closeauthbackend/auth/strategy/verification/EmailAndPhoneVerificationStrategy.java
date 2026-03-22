package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.auth.service.OtpService;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.notification.service.EmailService;
import com.anterka.closeauthbackend.notification.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Verification strategy for EMAIL_AND_PHONE mode.
 * Requires both email and phone verification before activation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailAndPhoneVerificationStrategy implements VerificationStrategy {

    private final OtpService otpService;
    private final EmailService emailService;
    private final SmsService smsService;

    @Override
    public void initiate(RegistrationData registrationData) {
        String email = registrationData.registrationDto().email();
        String phone = registrationData.registrationDto().phone();

        if (phone == null || phone.isBlank()) {
            throw new UserRegistrationException("Phone number is required for email and phone verification");
        }

        log.info("Initiating email and phone verification for: email={}, phone={}", email, phone);

        // Send email OTP
        String emailOtp = otpService.generateOtp();
        otpService.saveOtp(email, emailOtp);

        emailService.sendOTPMail(email, emailOtp)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to send email OTP to {}: {}", email, throwable.getMessage());
                    } else if (!success) {
                        log.warn("Email OTP sending returned false for {}", email);
                    } else {
                        log.info("Email OTP sent successfully to {}", email);
                    }
                });

        // Send phone OTP
        String phoneOtp = otpService.generateOtp();
        otpService.savePhoneOtp(phone, phoneOtp);

        smsService.sendOtp(phone, phoneOtp)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to send SMS OTP to {}: {}", phone, throwable.getMessage());
                    } else if (!success) {
                        log.warn("SMS OTP sending returned false for {}", phone);
                    } else {
                        log.info("SMS OTP sent successfully to {}", phone);
                    }
                });
    }

    @Override
    public Set<VerificationType> getRequiredVerificationTypes() {
        return Set.of(VerificationType.EMAIL, VerificationType.PHONE);
    }
}


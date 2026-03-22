package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.auth.service.OtpService;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.notification.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Verification strategy for PHONE mode.
 * Sends SMS OTP and requires phone verification before activation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PhoneVerificationStrategy implements VerificationStrategy {

    private final OtpService otpService;
    private final SmsService smsService;

    @Override
    public void initiate(RegistrationData registrationData) {
        String phone = registrationData.registrationDto().phone();

        if (phone == null || phone.isBlank()) {
            throw new UserRegistrationException("Phone number is required for phone verification");
        }

        String otp = otpService.generateOtp();
        otpService.savePhoneOtp(phone, otp);

        log.info("Initiating phone verification for: {}", phone);

        smsService.sendOtp(phone, otp)
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
        return Set.of(VerificationType.PHONE);
    }
}


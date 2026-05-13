package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.cache.repository.OtpRedisRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Service for OTP (One-Time Password) generation and management.
 * Supports both email and phone OTP verification.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpRedisRepository otpRepository;
    private final CloseAuthProperties properties;

    private static final SecureRandom random = new SecureRandom();
    private static final String PHONE_OTP_PREFIX = "phone_";

    /**
     * @return OTP validity in seconds (from configuration)
     */
    public long getOtpValiditySeconds() {
        return properties.getOtp().getValiditySeconds();
    }

    /**
     * Saves an OTP for the given email.
     * @return The TTL in seconds
     */
    public long saveOtp(String email, String otp) {
        long validity = getOtpValiditySeconds();
        otpRepository.saveOtp(email, otp, validity);
        return validity;
    }

    /**
     * Retrieves the OTP for the given email.
     */
    public Optional<String> getOtp(String email) {
        return otpRepository.getOtp(email);
    }

    /**
     * Deletes the OTP for the given email.
     */
    public void deleteOtp(String email) {
        otpRepository.deleteOtp(email);
    }

    /**
     * Generates a random numeric OTP using configured length.
     */
    public String generateOtp() {
        int length = properties.getOtp().getLength();
        StringBuilder otp = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            otp.append(random.nextInt(10));
        }
        return otp.toString();
    }

    /**
     * Validates an OTP against the stored value for email.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public boolean validateOtp(String email, String providedOtp) {
        return getOtp(email)
                .map(storedOtp -> constantTimeEquals(storedOtp, providedOtp))
                .orElse(false);
    }

    // ========== Phone OTP Methods ==========

    /**
     * Saves an OTP for the given phone number.
     * @return The TTL in seconds
     */
    public long savePhoneOtp(String phone, String otp) {
        String key = PHONE_OTP_PREFIX + phone;
        long validity = getOtpValiditySeconds();
        otpRepository.saveOtp(key, otp, validity);
        return validity;
    }

    /**
     * Retrieves the OTP for the given phone number.
     */
    public Optional<String> getPhoneOtp(String phone) {
        String key = PHONE_OTP_PREFIX + phone;
        return otpRepository.getOtp(key);
    }

    /**
     * Deletes the OTP for the given phone number.
     */
    public void deletePhoneOtp(String phone) {
        String key = PHONE_OTP_PREFIX + phone;
        otpRepository.deleteOtp(key);
    }

    /**
     * Validates a phone OTP against the stored value.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public boolean validatePhoneOtp(String phone, String providedOtp) {
        return getPhoneOtp(phone)
                .map(storedOtp -> constantTimeEquals(storedOtp, providedOtp))
                .orElse(false);
    }

    /**
     * Constant-time string comparison to prevent timing attacks on OTP validation.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}

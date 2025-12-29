package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.cache.repository.OtpRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service for OTP (One-Time Password) generation and management.
 * Supports both email and phone OTP verification.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpRedisRepository otpRepository;

    private static final SecureRandom random = new SecureRandom();
    private static final int OTP_LENGTH = 6;
    public static final long OTP_VALIDITY_SECONDS = TimeUnit.MINUTES.toSeconds(10); // 10 minutes
    private static final String PHONE_OTP_PREFIX = "phone_";

    /**
     * Saves an OTP for the given email.
     * @return The TTL in seconds
     */
    public long saveOtp(String email, String otp) {
        otpRepository.saveOtp(email, otp, OTP_VALIDITY_SECONDS);
        return OTP_VALIDITY_SECONDS;
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
     * Generates a random numeric OTP.
     */
    public String generateOtp() {
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(random.nextInt(10));
        }
        return otp.toString();
    }

    /**
     * Validates an OTP against the stored value for email.
     */
    public boolean validateOtp(String email, String providedOtp) {
        return getOtp(email)
                .map(storedOtp -> storedOtp.equals(providedOtp))
                .orElse(false);
    }

    // ========== Phone OTP Methods ==========

    /**
     * Saves an OTP for the given phone number.
     * @return The TTL in seconds
     */
    public long savePhoneOtp(String phone, String otp) {
        String key = PHONE_OTP_PREFIX + phone;
        otpRepository.saveOtp(key, otp, OTP_VALIDITY_SECONDS);
        return OTP_VALIDITY_SECONDS;
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
     */
    public boolean validatePhoneOtp(String phone, String providedOtp) {
        return getPhoneOtp(phone)
                .map(storedOtp -> storedOtp.equals(providedOtp))
                .orElse(false);
    }
}

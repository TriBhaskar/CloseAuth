package com.anterka.closeauthbackend.cache.service;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.cache.repository.RegistrationCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing registration data in cache.
 */
@Service
@RequiredArgsConstructor
public class RegistrationCacheService {

    private final RegistrationCacheRepository registrationRepository;

    private static final long REGISTRATION_VALIDITY_SECONDS = TimeUnit.HOURS.toSeconds(2);

    /**
     * Saves registration data for email verification flow.
     */
    public void saveRegistration(String email, RegistrationData registrationRequest) {
        registrationRepository.saveRegistrationData(email, registrationRequest, REGISTRATION_VALIDITY_SECONDS);
    }

    /**
     * Retrieves registration data for the given email.
     */
    public Optional<RegistrationData> getRegistration(String email) {
        return registrationRepository.getRegistrationData(email, RegistrationData.class);
    }

    /**
     * Checks if a pending registration exists for the given email.
     */
    public boolean registrationExists(String email) {
        return registrationRepository.registrationExists(email);
    }

    /**
     * Deletes registration data after successful verification.
     */
    public void deleteRegistration(String email) {
        registrationRepository.deleteRegistration(email);
    }
}

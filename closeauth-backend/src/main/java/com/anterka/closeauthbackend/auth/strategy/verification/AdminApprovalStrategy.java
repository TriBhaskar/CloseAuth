package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.cache.service.AdminPendingRegistrationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Verification strategy for ADMIN_APPROVAL mode.
 * User registration is queued for admin approval.
 * No OTP is sent; admin must manually approve the registration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminApprovalStrategy implements VerificationStrategy {

    private final AdminPendingRegistrationCacheService adminPendingCacheService;

    @Override
    public void initiate(RegistrationData registrationData) {
        String email = registrationData.registrationDto().email();
        String clientId = registrationData.clientId();

        log.info("Queuing registration for admin approval: email={}, clientId={}", email, clientId);

        // Save to admin pending cache for dashboard display
        adminPendingCacheService.savePendingApproval(clientId, email, registrationData);

        log.info("Registration queued for admin approval: {}", email);
    }

    @Override
    public Set<VerificationType> getRequiredVerificationTypes() {
        return Set.of(VerificationType.ADMIN_APPROVAL);
    }
}


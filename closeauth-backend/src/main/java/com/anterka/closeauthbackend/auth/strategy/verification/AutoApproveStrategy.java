package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * Verification strategy for AUTO_APPROVE mode.
 * User is activated immediately after registration without any verification.
 */
@Component
@Slf4j
public class AutoApproveStrategy implements VerificationStrategy {

    @Override
    public void initiate(RegistrationData registrationData) {
        String email = registrationData.registrationDto().email();
        log.info("Auto-approving registration for: {}", email);
        // No action needed - user will be persisted immediately
    }

    @Override
    public Set<VerificationType> getRequiredVerificationTypes() {
        return Collections.emptySet();
    }

    @Override
    public boolean requiresImmediatePersistence() {
        return true;
    }
}


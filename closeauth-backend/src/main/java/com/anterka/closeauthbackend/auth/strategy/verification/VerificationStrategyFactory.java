package com.anterka.closeauthbackend.auth.strategy.verification;

import com.anterka.closeauthbackend.auth.enums.VerificationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Factory for selecting the appropriate verification strategy based on client configuration.
 */
@Component
@RequiredArgsConstructor
public class VerificationStrategyFactory {

    private final EmailVerificationStrategy emailVerificationStrategy;
    private final PhoneVerificationStrategy phoneVerificationStrategy;
    private final AdminApprovalStrategy adminApprovalStrategy;
    private final EmailAndPhoneVerificationStrategy emailAndPhoneVerificationStrategy;
    private final AutoApproveStrategy autoApproveStrategy;

    /**
     * Returns the appropriate verification strategy based on the verification mode.
     *
     * @param verificationMode The verification mode configured for the client
     * @return The corresponding VerificationStrategy implementation
     * @throws IllegalArgumentException if the verification mode is unknown
     */
    public VerificationStrategy getStrategy(VerificationMode verificationMode) {
        return switch (verificationMode) {
            case EMAIL -> emailVerificationStrategy;
            case PHONE -> phoneVerificationStrategy;
            case ADMIN_APPROVAL -> adminApprovalStrategy;
            case EMAIL_AND_PHONE -> emailAndPhoneVerificationStrategy;
            case AUTO_APPROVE -> autoApproveStrategy;
        };
    }

    /**
     * Returns the appropriate verification strategy based on the verification mode string.
     * Used when loading from database configuration.
     *
     * @param verificationModeString The verification mode as a string
     * @return The corresponding VerificationStrategy implementation
     * @throws IllegalArgumentException if the verification mode is unknown
     */
    public VerificationStrategy getStrategy(String verificationModeString) {
        VerificationMode mode = VerificationMode.valueOf(verificationModeString.toUpperCase());
        return getStrategy(mode);
    }
}


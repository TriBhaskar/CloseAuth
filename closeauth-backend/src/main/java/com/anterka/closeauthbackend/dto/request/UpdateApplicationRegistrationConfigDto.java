package com.anterka.closeauthbackend.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateApplicationRegistrationConfigDto {

    @Pattern(regexp = "EMAIL|PHONE|ADMIN_APPROVAL|EMAIL_AND_PHONE|AUTO_APPROVE",
            message = "Invalid verification method")
    private String verificationMethod;

    private Boolean requireEmailVerification;

    private Boolean requirePhoneVerification;

    private Boolean requireAdminApproval;

    private String autoApproveDomains; // JSON array

    private Boolean allowSelfRegistration;

    private Boolean registrationEnabled;

    private Boolean requirePhone;

    private Boolean requireFirstName;

    private Boolean requireLastName;

    private String customFields; // JSON array

    private String verificationEmailTemplate;

    @Min(value = 1, message = "Token expiry must be at least 1 hour")
    @Max(value = 168, message = "Token expiry must not exceed 168 hours (7 days)")
    private Integer verificationTokenExpiry;

    @Pattern(regexp = "SMS|CALL|WHATSAPP", message = "Invalid phone verification method")
    private String phoneVerificationMethod;

    @Min(value = 1, message = "Phone token expiry must be at least 1 minute")
    @Max(value = 60, message = "Phone token expiry must not exceed 60 minutes")
    private Integer phoneVerificationTokenExpiry;

    @Email(message = "Invalid approval notification email")
    private String approvalNotificationEmail;

    private String approvalRequiredMessage;

    private Boolean welcomeEmailEnabled;

    @Size(max = 500, message = "Redirect URL must not exceed 500 characters")
    private String redirectAfterRegistration;
}


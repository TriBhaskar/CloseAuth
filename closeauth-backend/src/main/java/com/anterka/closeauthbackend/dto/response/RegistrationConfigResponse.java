package com.anterka.closeauthbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationConfigResponse {

    private Integer id;
    private String clientId;
    private String verificationMethod;
    private Boolean requireEmailVerification;
    private Boolean requirePhoneVerification;
    private Boolean requireAdminApproval;
    private String autoApproveDomains;
    private Boolean allowSelfRegistration;
    private Boolean registrationEnabled;
    private Boolean requirePhone;
    private Boolean requireFirstName;
    private Boolean requireLastName;
    private String customFields;
    private String verificationEmailTemplate;
    private Integer verificationTokenExpiry;
    private String phoneVerificationMethod;
    private Integer phoneVerificationTokenExpiry;
    private String approvalNotificationEmail;
    private String approvalRequiredMessage;
    private Boolean welcomeEmailEnabled;
    private String redirectAfterRegistration;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}


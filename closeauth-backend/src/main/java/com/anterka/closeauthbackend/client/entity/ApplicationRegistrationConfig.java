package com.anterka.closeauthbackend.client.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "application_registration_config")
public class ApplicationRegistrationConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "client_id", nullable = false, unique = true)
    private Client client;

    @Column(name = "verification_method", length = 50, nullable = false)
    private String verificationMethod; // EMAIL, PHONE, ADMIN_APPROVAL, EMAIL_AND_PHONE, AUTO_APPROVE

    @Column(name = "require_email_verification")
    private Boolean requireEmailVerification = true;

    @Column(name = "require_phone_verification")
    private Boolean requirePhoneVerification = false;

    @Column(name = "require_admin_approval")
    private Boolean requireAdminApproval = false;

    @Column(name = "auto_approve_domains", columnDefinition = "TEXT")
    private String autoApproveDomains; // JSON array of whitelisted email domains

    @Column(name = "allow_self_registration")
    private Boolean allowSelfRegistration = true;

    @Column(name = "registration_enabled")
    private Boolean registrationEnabled = true;

    @Column(name = "require_phone")
    private Boolean requirePhone = false;

    @Column(name = "require_first_name")
    private Boolean requireFirstName = true;

    @Column(name = "require_last_name")
    private Boolean requireLastName = true;

    @Column(name = "custom_fields", columnDefinition = "TEXT")
    private String customFields; // JSON array of custom fields

    @Column(name = "verification_email_template", columnDefinition = "TEXT")
    private String verificationEmailTemplate;

    @Column(name = "verification_token_expiry")
    private Integer verificationTokenExpiry = 24; // Hours

    @Column(name = "phone_verification_method", length = 20)
    private String phoneVerificationMethod = "SMS"; // SMS, CALL, WHATSAPP

    @Column(name = "phone_verification_token_expiry")
    private Integer phoneVerificationTokenExpiry = 10; // Minutes

    @Column(name = "approval_notification_email")
    private String approvalNotificationEmail;

    @Column(name = "approval_required_message", columnDefinition = "TEXT")
    private String approvalRequiredMessage;

    @Column(name = "welcome_email_enabled")
    private Boolean welcomeEmailEnabled = true;

    @Column(name = "redirect_after_registration", length = 500)
    private String redirectAfterRegistration;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}


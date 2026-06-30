package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.audit.service.AuditLogService;
import com.anterka.closeauthbackend.auth.enums.VerificationMode;
import com.anterka.closeauthbackend.client.dto.request.UpdateApplicationRegistrationConfigDto;
import com.anterka.closeauthbackend.client.dto.response.RegistrationConfigResponse;
import com.anterka.closeauthbackend.client.entity.ApplicationRegistrationConfig;
import com.anterka.closeauthbackend.client.entity.Client;
import com.anterka.closeauthbackend.client.repository.ApplicationRegistrationConfigRepository;
import com.anterka.closeauthbackend.client.repository.ClientRepository;
import com.anterka.closeauthbackend.user.security.UserActionContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationRegistrationConfigService {

    private final ApplicationRegistrationConfigRepository configRepository;
    private final ClientOwnershipVerifier ownershipVerifier;
    private final ClientRepository clientRepository;
    private final AuditLogService auditLogService;


    /**
     * Get registration config for a client
     */
    @Transactional(readOnly = true)
    public RegistrationConfigResponse getConfig(String clientId, UserActionContext context) {
        ownershipVerifier.verify(clientId, context.userId());

        ApplicationRegistrationConfig config = configRepository.findByClient_ClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Registration config not found for client"));

        return mapToResponse(config);
    }

    /**
     * Update registration config
     */
    @Transactional
    public RegistrationConfigResponse updateConfig(String clientId,
                                                   UpdateApplicationRegistrationConfigDto dto,
                                                   UserActionContext context) {
        ownershipVerifier.verify(clientId, context.userId());

        ApplicationRegistrationConfig config = configRepository.findByClient_ClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Registration config not found for client"));

        Map<String, Object> beforeState = captureState(config);

        // Update fields if provided
        if (dto.getVerificationMethod() != null) {
            config.setVerificationMethod(dto.getVerificationMethod());
        }
        if (dto.getRequireEmailVerification() != null) {
            config.setRequireEmailVerification(dto.getRequireEmailVerification());
        }
        if (dto.getRequirePhoneVerification() != null) {
            config.setRequirePhoneVerification(dto.getRequirePhoneVerification());
        }
        if (dto.getRequireAdminApproval() != null) {
            config.setRequireAdminApproval(dto.getRequireAdminApproval());
        }
        if (dto.getAutoApproveDomains() != null) {
            config.setAutoApproveDomains(dto.getAutoApproveDomains());
        }
        if (dto.getAllowSelfRegistration() != null) {
            config.setAllowSelfRegistration(dto.getAllowSelfRegistration());
        }
        if (dto.getRegistrationEnabled() != null) {
            config.setRegistrationEnabled(dto.getRegistrationEnabled());
        }
        if (dto.getRequirePhone() != null) {
            config.setRequirePhone(dto.getRequirePhone());
        }
        if (dto.getRequireFirstName() != null) {
            config.setRequireFirstName(dto.getRequireFirstName());
        }
        if (dto.getRequireLastName() != null) {
            config.setRequireLastName(dto.getRequireLastName());
        }
        if (dto.getCustomFields() != null) {
            config.setCustomFields(dto.getCustomFields());
        }
        if (dto.getVerificationEmailTemplate() != null) {
            config.setVerificationEmailTemplate(dto.getVerificationEmailTemplate());
        }
        if (dto.getVerificationTokenExpiry() != null) {
            config.setVerificationTokenExpiry(dto.getVerificationTokenExpiry());
        }
        if (dto.getPhoneVerificationMethod() != null) {
            config.setPhoneVerificationMethod(dto.getPhoneVerificationMethod());
        }
        if (dto.getPhoneVerificationTokenExpiry() != null) {
            config.setPhoneVerificationTokenExpiry(dto.getPhoneVerificationTokenExpiry());
        }
        if (dto.getApprovalNotificationEmail() != null) {
            config.setApprovalNotificationEmail(dto.getApprovalNotificationEmail());
        }
        if (dto.getApprovalRequiredMessage() != null) {
            config.setApprovalRequiredMessage(dto.getApprovalRequiredMessage());
        }
        if (dto.getWelcomeEmailEnabled() != null) {
            config.setWelcomeEmailEnabled(dto.getWelcomeEmailEnabled());
        }
        if (dto.getRedirectAfterRegistration() != null) {
            config.setRedirectAfterRegistration(dto.getRedirectAfterRegistration());
        }

        ApplicationRegistrationConfig updated = configRepository.save(config);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("before", beforeState);
        metadata.put("after", captureState(updated));
        auditLogService.logAction(clientId, context.userId(), "REGISTRATION_CONFIG_UPDATED",
                context.ipAddress(), context.userAgent(), metadata);

        log.info("Updated registration config for client: {}", clientId);
        return mapToResponse(updated);
    }

    /**
     * Create default registration config
     */
    @Transactional
    public ApplicationRegistrationConfig createDefaultConfig(String clientId) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        // Check if config already exists
        if (configRepository.findByClient_ClientId(clientId).isPresent()) {
            log.info("Registration config already exists for client: {}", clientId);
            return configRepository.findByClient_ClientId(clientId).get();
        }

        ApplicationRegistrationConfig config = ApplicationRegistrationConfig.builder()
                .client(client)
                .verificationMethod(VerificationMode.EMAIL.name())
                .requireEmailVerification(true)
                .requirePhoneVerification(false)
                .requireAdminApproval(false)
                .allowSelfRegistration(true)
                .registrationEnabled(true)
                .requirePhone(false)
                .requireFirstName(true)
                .requireLastName(true)
                .verificationTokenExpiry(24)
                .phoneVerificationMethod("SMS")
                .phoneVerificationTokenExpiry(10)
                .welcomeEmailEnabled(true)
                .build();

        ApplicationRegistrationConfig saved = configRepository.save(config);
        log.info("Created default registration config for client: {}", clientId);
        return saved;
    }

    /**
     * Capture current state for audit logging
     */
    private Map<String, Object> captureState(ApplicationRegistrationConfig config) {
        Map<String, Object> state = new HashMap<>();
        state.put("verificationMethod", config.getVerificationMethod());
        state.put("requireEmailVerification", config.getRequireEmailVerification());
        state.put("requirePhoneVerification", config.getRequirePhoneVerification());
        state.put("allowSelfRegistration", config.getAllowSelfRegistration());
        state.put("registrationEnabled", config.getRegistrationEnabled());
        state.put("verificationTokenExpiry", config.getVerificationTokenExpiry());
        return state;
    }

    /**
     * Map entity to response DTO
     */
    private RegistrationConfigResponse mapToResponse(ApplicationRegistrationConfig config) {
        return RegistrationConfigResponse.builder()
                .id(config.getId())
                .clientId(config.getClient().getId())
                .verificationMethod(config.getVerificationMethod())
                .requireEmailVerification(config.getRequireEmailVerification())
                .requirePhoneVerification(config.getRequirePhoneVerification())
                .requireAdminApproval(config.getRequireAdminApproval())
                .autoApproveDomains(config.getAutoApproveDomains())
                .allowSelfRegistration(config.getAllowSelfRegistration())
                .registrationEnabled(config.getRegistrationEnabled())
                .requirePhone(config.getRequirePhone())
                .requireFirstName(config.getRequireFirstName())
                .requireLastName(config.getRequireLastName())
                .customFields(config.getCustomFields())
                .verificationEmailTemplate(config.getVerificationEmailTemplate())
                .verificationTokenExpiry(config.getVerificationTokenExpiry())
                .phoneVerificationMethod(config.getPhoneVerificationMethod())
                .phoneVerificationTokenExpiry(config.getPhoneVerificationTokenExpiry())
                .approvalNotificationEmail(config.getApprovalNotificationEmail())
                .approvalRequiredMessage(config.getApprovalRequiredMessage())
                .welcomeEmailEnabled(config.getWelcomeEmailEnabled())
                .redirectAfterRegistration(config.getRedirectAfterRegistration())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}

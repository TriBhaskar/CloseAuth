package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.tenant.dto.RegistrationConfigView;
import com.anterka.closeauthbackend.tenant.entity.TenantRegistrationConfig;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import com.anterka.closeauthbackend.tenant.repository.TenantRegistrationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves a tenant's registration mode (§9.2). Reads the tenant's {@code tenant_registration_config} row; if absent
 * (a tenant provisioned before Stage 6b-i) or misconfigured, falls back to the platform default
 * ({@code closeauth.registration.default-mode}). New tenants get a row at provisioning
 * ({@code RegistrationConfigProvisioningCallback}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationConfigService {

    private final TenantRegistrationConfigRepository repository;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    @Transactional(readOnly = true)
    public RegistrationMode resolveMode(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .map(config -> config.getMode())
                .orElseGet(this::platformDefault);
    }

    /** The tenant's current registration config (mode), for the admin surface (§7.8). */
    @Transactional(readOnly = true)
    public RegistrationConfigView getConfig(UUID tenantId) {
        return new RegistrationConfigView(tenantId, resolveMode(tenantId));
    }

    /**
     * Sets the tenant's registration mode (§7.8). Upserts the {@code tenant_registration_config} row (a tenant
     * provisioned before 6b-i may lack one). The mode is the policy: OPEN / EMAIL_VERIFIED / ADMIN_APPROVED / INVITE_ONLY.
     */
    @Transactional
    public RegistrationConfigView updateMode(UUID tenantId, RegistrationMode mode) {
        TenantRegistrationConfig config = repository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    TenantRegistrationConfig fresh = new TenantRegistrationConfig();
                    fresh.setTenantId(tenantId);
                    return fresh;
                });
        config.setMode(mode);
        repository.save(config);
        auditEmitter.emit(AuditEvents.tenantRegistrationPolicyChanged(tenantId, mode.name()));
        return new RegistrationConfigView(tenantId, mode);
    }

    /** The platform-default mode, parsed defensively (bad config → EMAIL_VERIFIED, the safe default). */
    public RegistrationMode platformDefault() {
        String configured = properties.getRegistration().getDefaultMode();
        try {
            return RegistrationMode.valueOf(configured);
        } catch (IllegalArgumentException | NullPointerException badConfig) {
            log.warn("Invalid closeauth.registration.default-mode='{}'; falling back to EMAIL_VERIFIED", configured);
            return RegistrationMode.EMAIL_VERIFIED;
        }
    }
}

package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.tenant.dto.RegistrationConfigView;
import com.anterka.closeauthbackend.tenant.dto.UpdateRegistrationConfigCommand;
import com.anterka.closeauthbackend.tenant.service.RegistrationConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant registration-config administration (§7.8) — where a tenant admin sets the registration mode
 * ({@code OPEN | EMAIL_VERIFIED | ADMIN_APPROVED | INVITE_ONLY}). {@link RequiresTenantAccess} gate. Wraps
 * {@link RegistrationConfigService} (6b-i).
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/tenants/{tenantId}/registration-config} · {@code PUT .../registration-config} (set mode).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}/registration-config")
public class RegistrationConfigController {

    private final RegistrationConfigService registrationConfigService;

    @GetMapping
    public RegistrationConfigView get(@PathVariable String tenantId) {
        return registrationConfigService.getConfig(UUID.fromString(tenantId));
    }

    @PutMapping
    public RegistrationConfigView update(@PathVariable String tenantId,
                                         @Valid @RequestBody UpdateRegistrationConfigCommand command) {
        return registrationConfigService.updateMode(UUID.fromString(tenantId), command.mode());
    }
}

package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.token.dto.ConsentView;
import com.anterka.closeauthbackend.token.service.TenantAwareOAuth2AuthorizationConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Consent management (§7.8) — list/revoke a user's granted OAuth2 consents. {@link RequiresTenantAccess} gate; the
 * consent service is tenant-scoped (never crosses tenants). Wraps SAS's tenant-aware consent service (4a/6b-ii). The
 * principal name on a consent row is the user's {@code sub} (Stage 6a made it the user UUID).
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/tenants/{tenantId}/users/{userId}/consents} · {@code DELETE .../consents/{registeredClientId}} (revoke).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}/users/{userId}/consents")
public class ConsentAdminController {

    private final TenantAwareOAuth2AuthorizationConsentService consentService;

    @GetMapping
    public List<ConsentView> list(@PathVariable String tenantId, @PathVariable UUID userId) {
        return consentService.listConsents(UUID.fromString(tenantId), userId.toString());
    }

    @DeleteMapping("/{registeredClientId}")
    public ResponseEntity<Void> revoke(@PathVariable String tenantId, @PathVariable UUID userId,
                                       @PathVariable String registeredClientId) {
        consentService.revokeConsent(UUID.fromString(tenantId), registeredClientId, userId.toString());
        return ResponseEntity.noContent().build();
    }
}

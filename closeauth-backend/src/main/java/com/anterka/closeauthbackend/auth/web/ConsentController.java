package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.dto.ConsentContext;
import com.anterka.closeauthbackend.auth.dto.ConsentScopeView;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.ConsentScopeResolver;
import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The consent-page context endpoint (Stage 6b-ii). SAS redirects here (its configured {@code consentPage}) when a
 * non-trusted client needs consent; the hosted UI renders this JSON and POSTs the decision back to
 * {@code /oauth2/authorize}.
 *
 * <p>Because the authenticated {@code SecurityContext} is NOT persisted to the servlet session (6a's tenant-scoping
 * design), this GET reconstructs the user from the session cookie (best-effort, tenant-scoped) only to look up
 * previously-granted scopes; the render itself needs only {@code client_id}/{@code scope}/{@code state}. The consent
 * <em>submission</em> (POST {@code /oauth2/authorize}) is authenticated by {@code TenantSessionSsoFilter}, and SAS
 * persists the decision via the 4a tenant-aware consent service.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /oauth2/consent?client_id=&scope=&state=} → 200 {@link ConsentContext}
 * {@code {clientId, clientName, state, scopes:[{scope,description,requiresConsent}], alreadyGranted:[...]}}.
 * The UI POSTs {@code client_id}, {@code state}, and one {@code scope} param per approved scope to
 * {@code /oauth2/authorize} to approve; omitting scopes / a deny yields the OAuth {@code access_denied} error.
 */
@RestController
@RequiredArgsConstructor
public class ConsentController {

    private final AuthFlowTenantResolver tenantResolver;
    private final ConsentScopeResolver consentScopeResolver;
    private final SessionCookieManager cookieManager;
    private final AuthServerSessionService sessionService;
    private final OAuth2AuthorizationConsentService authorizationConsentService;

    @GetMapping(value = "/oauth2/consent", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConsentContext> consent(
            @RequestParam("client_id") String clientId,
            @RequestParam("scope") String scope,
            @RequestParam("state") String state,
            HttpServletRequest request) {

        Optional<RegisteredClient> client = tenantResolver.findClient(clientId);
        if (client.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        RegisteredClient registeredClient = client.get();
        UUID tenantId = CloseAuthClientSettings.getTenantId(registeredClient);

        List<String> requested = Arrays.stream(scope.split(" ")).filter(s -> !s.isBlank()).toList();
        List<ConsentScopeView> scopes = consentScopeResolver.resolveAll(tenantId, requested);
        List<String> alreadyGranted = alreadyGranted(request, registeredClient, tenantId);

        return ResponseEntity.ok(new ConsentContext(
                clientId, registeredClient.getClientName(), state, scopes, alreadyGranted));
    }

    /** Previously-consented scopes for this user+client (tenant-scoped), or empty if unresolvable. */
    private List<String> alreadyGranted(HttpServletRequest request, RegisteredClient registeredClient, UUID tenantId) {
        Optional<String> sessionKey = cookieManager.readSessionKey(request);
        if (sessionKey.isEmpty() || tenantId == null) {
            return List.of();
        }
        Optional<SessionView> session = sessionService.validateSession(sessionKey.get(), tenantId);
        if (session.isEmpty()) {
            return List.of();
        }
        OAuth2AuthorizationConsent consent = authorizationConsentService.findById(
                registeredClient.getId(), session.get().userId().toString());
        return consent == null ? List.of() : List.copyOf(consent.getScopes());
    }
}

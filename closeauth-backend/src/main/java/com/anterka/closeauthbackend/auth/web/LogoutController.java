package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/**
 * RP-initiated logout (Stage 6a). Kills the Auth Server session with Stage 5's full four-leg cascade (Redis hot entry
 * + ledger row + refresh families + access-token revocation marker), clears the session cookie, and — if a valid,
 * registered {@code post_logout_redirect_uri} is supplied — redirects there; otherwise returns 204.
 *
 * <p><b>Open-redirect safe:</b> a supplied {@code post_logout_redirect_uri} is honored ONLY if it is registered on the
 * given client (its {@code postLogoutRedirectUris}, falling back to its {@code redirectUris}); anything else is ignored
 * (204). Idempotent: logging out with no/invalid session cookie still clears the cookie and returns 204.
 *
 * <p><b>Documented seam:</b> OIDC back-channel logout (signed logout tokens POSTed to each client's
 * back-channel URI) and full RP-initiated OIDC semantics ({@code id_token_hint} validation) are not implemented here;
 * the client back-channel URIs are not yet modelled. The session-revocation cascade — the security-relevant half — is
 * complete. TODO(6b/later): back-channel notification.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class LogoutController {

    private final AuthServerSessionService sessionService;
    private final SessionCookieManager cookieManager;
    private final AuthFlowTenantResolver tenantResolver;
    private final AuditEmitter auditEmitter;

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestParam(value = "post_logout_redirect_uri", required = false) String postLogoutRedirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            HttpServletRequest request,
            HttpServletResponse response) {

        cookieManager.readSessionKey(request).ifPresent(sessionKey ->
                sessionService.revokeSession(sessionKey).ifPresent(revoked -> { // Stage 5 four-leg cascade
                    log.info("Logout: session revoked (cascade applied)");
                    auditEmitter.emit(AuditEvents.logout(revoked.tenantId(), revoked.userId()));
                }));
        cookieManager.clear(response);

        Optional<URI> target = validatedRedirect(clientId, postLogoutRedirectUri);
        return target
                .map(uri -> ResponseEntity.status(HttpStatus.FOUND).location(uri).<Void>build())
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Returns the redirect URI only if it is registered on the client (else empty — never an open redirect). */
    private Optional<URI> validatedRedirect(String clientId, String postLogoutRedirectUri) {
        if (clientId == null || postLogoutRedirectUri == null || postLogoutRedirectUri.isBlank()) {
            return Optional.empty();
        }
        return tenantResolver.findClient(clientId)
                .filter(client -> isRegistered(client, postLogoutRedirectUri))
                .map(client -> URI.create(postLogoutRedirectUri));
    }

    private boolean isRegistered(RegisteredClient client, String uri) {
        return client.getPostLogoutRedirectUris().contains(uri) || client.getRedirectUris().contains(uri);
    }
}

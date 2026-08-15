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
import org.springframework.web.bind.annotation.GetMapping;
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
 *
 * <p><b>GET as well as POST:</b> a cross-origin caller (e.g. the Go BFF fronting the tenant-admin console — see
 * {@code CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md}) cannot reach this endpoint with {@code CLOSEAUTH_SESSION} attached
 * via {@code fetch()} or a cross-site form POST: the cookie is {@code SameSite=Lax}, which is only sent on a
 * top-level GET navigation, exactly like the {@code /oauth2/authorize} redirect this cookie already relies on (see
 * {@link SessionCookieManager}). GET is safe here without CSRF protection for the same reason POST already has none
 * on this chain (see {@code AuthorizationServerConfig}'s {@code defaultSecurityFilterChain} javadoc): the only effect
 * is ending the caller's own session, never a state change useful to force onto a victim.
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
        return doLogout(postLogoutRedirectUri, clientId, request, response);
    }

    /** See class javadoc, "GET as well as POST". */
    @GetMapping("/logout")
    public ResponseEntity<Void> logoutViaNavigation(
            @RequestParam(value = "post_logout_redirect_uri", required = false) String postLogoutRedirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            HttpServletRequest request,
            HttpServletResponse response) {
        return doLogout(postLogoutRedirectUri, clientId, request, response);
    }

    private ResponseEntity<Void> doLogout(String postLogoutRedirectUri, String clientId,
                                          HttpServletRequest request, HttpServletResponse response) {
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

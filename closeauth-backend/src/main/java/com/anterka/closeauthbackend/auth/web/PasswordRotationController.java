package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.enums.AuthMethod;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService.RotationOutcome;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService.RotationResult;
import com.anterka.closeauthbackend.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Forced-password-rotation confirm endpoint (Phase 2, §2.2.3). Sibling to {@link PasswordResetController} — a
 * dedicated endpoint, not a branch inside it, because {@code PasswordResetService.resetPassword} hardcodes the
 * {@code PASSWORD_RESET} purpose and has scenario-specific side effects (§1.13). Tenant-scoped via {@code client_id},
 * matching every other endpoint in this package.
 *
 * <h2>HTTP contract</h2>
 * <ul>
 *   <li>{@code POST /password-rotation/confirm} (form: {@code token}, {@code password}, {@code client_id},
 *       {@code authorize_query?}) → on success establishes the tenant-scoped session (a real {@code Set-Cookie} —
 *       the FIRST point in this flow a session is ever issued) and <b>302</b>s to the resumed
 *       {@code /oauth2/authorize} request (or the BFF default if {@code authorize_query} was absent — the emailed-
 *       link on-ramp, §2.2.2's on-ramp 2); <b>400</b> generic on an invalid/expired/used/stale token or a malformed
 *       {@code authorize_query} (never says which — no enumeration).</li>
 * </ul>
 *
 * <p><b>{@code authorize_query} is attacker-controllable but not an open redirect</b> (traced in the plan §5):
 * {@link LoginSuccessResponder} builds the final redirect as {@code <issuerUrl><authorizationEndpoint>?<authorizeQuery>}
 * — scheme/host/path are server-config-derived, only the query is caller-supplied, exactly the same shape
 * {@code POST /login} already accepts today. This endpoint adds no semantic validation of its contents (it stays
 * opaque, passed straight through) beyond rejecting control characters and an unreasonable length, purely so a
 * malformed value is a 400 here rather than an {@link IllegalArgumentException} 500 from {@link URI#create}
 * downstream — checked BEFORE the token is consumed, so a malformed request never burns the caller's one-time token.
 */
@RestController
@RequiredArgsConstructor
public class PasswordRotationController {

    /**
     * Generous upper bound on {@code authorize_query} length — well above any realistic {@code /oauth2/authorize}
     * query string. Purely a defensive cap, not a semantic validation.
     */
    private static final int MAX_AUTHORIZE_QUERY_LENGTH = 4096;

    private final AuthFlowTenantResolver tenantResolver;
    private final PasswordRotationService passwordRotationService;
    private final LoginSuccessResponder loginSuccessResponder;

    @PostMapping(value = "/password-rotation/confirm", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> confirm(
            @RequestParam("token") String token,
            @RequestParam("password") String password,
            @RequestParam("client_id") String clientId,
            @RequestParam(value = "authorize_query", required = false) String authorizeQuery,
            HttpServletRequest request,
            HttpServletResponse response) {
        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        if (tenantId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!isWellFormed(authorizeQuery)) {
            return ResponseEntity.badRequest().build();
        }

        RotationResult result = passwordRotationService.completeRotation(
                TenantContext.of(tenantId.get()), token, password);
        if (result.outcome() != RotationOutcome.ROTATED) {
            return ResponseEntity.badRequest().build(); // generic — no enumeration of which check failed
        }

        // The ONLY point in this flow a session is established — mirrors LoginController's success path exactly,
        // reusing LoginSuccessResponder unmodified so the resume redirect is byte-identical to an ordinary login's.
        String redirectUrl = loginSuccessResponder.establishSessionAndResolveRedirect(request, response,
                tenantId.get(), result.userId(), AuthMethod.PASSWORD.amrValue(), false, authorizeQuery);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
    }

    /** Rejects control characters (including CR/LF) and an unreasonable length — see class javadoc / plan Finding 6. */
    private boolean isWellFormed(String authorizeQuery) {
        if (authorizeQuery == null) {
            return true; // absent is valid — the emailed-link on-ramp carries none
        }
        if (authorizeQuery.length() > MAX_AUTHORIZE_QUERY_LENGTH) {
            return false;
        }
        return authorizeQuery.chars().noneMatch(Character::isISOControl);
    }
}

package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresSelf;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.ChangePasswordCommand;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.platform.service.PlatformAdminService;
import com.anterka.closeauthbackend.session.dto.MeSessionView;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service (§7.8, {@code /v1/me/**}) — the authenticated caller managing THEIR OWN resources. {@link RequiresSelf}
 * (authenticated, not admin-gated); every method is <b>strictly self-scoped</b> to the token's {@code sub} — a caller
 * can NEVER reach another principal's profile/sessions. Works for both tenant users and platform admins.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/me} (own profile) · {@code GET /v1/me/sessions} (own sessions, no session_key) ·
 * {@code DELETE /v1/me/sessions/{sessionId}} (revoke own session — 4-leg cascade) ·
 * {@code POST /v1/me/change-password} (verify current, set new, revoke sessions per §13.4).
 */
@RestController
@RequiredArgsConstructor
@RequiresSelf
@RequestMapping("/v1/me")
public class MeController {

    private final UserService userService;
    private final PlatformAdminService platformAdminService;
    private final AuthServerSessionService sessionService;

    @GetMapping
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        UUID sub = UUID.fromString(jwt.getSubject());
        if (isPlatformAdmin(jwt)) {
            return ResponseEntity.ok(platformAdminService.getById(sub)); // platform profile
        }
        return ResponseEntity.ok(userService.getUserById(tenantContext(jwt), sub)); // tenant-user profile
    }

    @GetMapping("/sessions")
    public List<MeSessionView> sessions(@AuthenticationPrincipal Jwt jwt) {
        if (isPlatformAdmin(jwt)) {
            return List.of(); // platform-admin tokens are session-less (direct-mint, 7a)
        }
        UUID sub = UUID.fromString(jwt.getSubject());
        return sessionService.listSessionsForUser(tenantId(jwt), sub).stream().map(MeSessionView::from).toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        UUID sub = UUID.fromString(jwt.getSubject());
        SessionView session = sessionService.getById(sessionId)
                .orElseThrow(() -> notFound());
        // Strict self-scoping: a caller may revoke ONLY their own session (same sub AND tenant); else 404 (no leak).
        if (isPlatformAdmin(jwt) || !sub.equals(session.userId()) || !tenantId(jwt).equals(session.tenantId())) {
            throw notFound();
        }
        sessionService.revokeSession(session.sessionKey()); // Stage 5 four-leg cascade
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody ChangeOwnPasswordRequest request) {
        if (isPlatformAdmin(jwt)) {
            // Platform-admin self password change is out of scope for 7b (no self-service credential endpoint yet).
            throw new CloseAuthDomainException(ErrorCategory.FORBIDDEN, "platform_admin.no_self_password",
                    "Platform-admin self password change is not available here");
        }
        UUID sub = UUID.fromString(jwt.getSubject());
        TenantContext context = tenantContext(jwt);
        // userId comes from the TOKEN (sub), never the body — a caller can only change their own password.
        userService.changePassword(context, new ChangePasswordCommand(sub, request.currentPassword(), request.newPassword()));
        // §13.4: revoke the caller's sessions on password change (forces re-authentication).
        sessionService.revokeAllUserSessions(tenantId(jwt), sub);
        return ResponseEntity.noContent().build();
    }

    private boolean isPlatformAdmin(Jwt jwt) {
        return "platform_admin".equals(jwt.getClaimAsString("token_use")) || jwt.getClaimAsString("tenant_id") == null;
    }

    private UUID tenantId(Jwt jwt) {
        String claim = jwt.getClaimAsString("tenant_id");
        if (claim == null) {
            throw new CloseAuthDomainException(ErrorCategory.FORBIDDEN, "token.no_tenant", "Token has no tenant scope");
        }
        return UUID.fromString(claim);
    }

    private TenantContext tenantContext(Jwt jwt) {
        return TenantContext.of(tenantId(jwt));
    }

    private CloseAuthDomainException notFound() {
        return new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "session.not_found", "Session not found");
    }

    /** Self password-change body — the userId is taken from the token, never here. */
    public record ChangeOwnPasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }
}

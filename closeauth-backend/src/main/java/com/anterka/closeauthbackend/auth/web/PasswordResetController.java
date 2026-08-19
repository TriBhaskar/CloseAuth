package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.PasswordResetService;
import com.anterka.closeauthbackend.auth.service.PasswordResetService.ResetOutcome;
import com.anterka.closeauthbackend.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Password-reset endpoints (Stage 6b-i). Tenant-scoped via {@code client_id}.
 *
 * <h2>HTTP contract</h2>
 * <ul>
 *   <li>{@code POST /password-reset/request} (form: {@code email}, {@code client_id}) → <b>200</b> ALWAYS
 *       (enumeration-safe: identical response whether or not the account exists; a link is emailed only if it does).</li>
 *   <li>{@code POST /password-reset/confirm} (form: {@code token}, {@code password}, {@code client_id}) → <b>200</b>
 *       when the password is reset (and all sessions/tokens revoked — the post-reset cascade); <b>400</b> generic on
 *       an invalid/expired/used token (never says which).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class PasswordResetController {

    private final AuthFlowTenantResolver tenantResolver;
    private final PasswordResetService passwordResetService;

    @PostMapping(value = "/password-reset/request", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> requestReset(
            @RequestParam("email") String email,
            @RequestParam("client_id") String clientId) {
        tenantResolver.resolveTenantId(clientId).ifPresent(tenantId ->
                passwordResetService.requestReset(
                        TenantContext.of(tenantId), email, clientId, tenantResolver.resolveTenantSlug(clientId).orElse(null)));
        // Always 200 — never reveal whether the account exists.
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/password-reset/confirm", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> confirm(
            @RequestParam("token") String token,
            @RequestParam("password") String password,
            @RequestParam("client_id") String clientId) {
        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        if (tenantId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ResetOutcome outcome = passwordResetService.resetPassword(TenantContext.of(tenantId.get()), token, password);
        return outcome == ResetOutcome.RESET
                ? ResponseEntity.ok().build()
                : ResponseEntity.badRequest().build(); // generic — no enumeration
    }
}

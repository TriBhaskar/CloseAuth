package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService.VerificationOutcome;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Email / OTP verification endpoints (Stage 6b-i). Tenant-scoped via {@code client_id}.
 *
 * <h2>HTTP contract</h2>
 * <ul>
 *   <li>{@code POST /verify-email/request} (form: {@code email}, {@code client_id}) → <b>200</b> always (a code is
 *       (re)sent if applicable; rate-limited silently). Resend for a PENDING user.</li>
 *   <li>{@code POST /verify-email/confirm} (form: {@code email}, {@code code}, {@code client_id}) → <b>200</b> when
 *       verified — INCLUDING a code that was already used (FE-2d, spec §6.2.4: re-clicking an old email link is
 *       success, not an error); <b>410</b> when the code has expired ({@code Send a new one} is the frontend's own
 *       action, not a distinct endpoint); <b>400</b> generic {@code invalid} for every other failure (never says
 *       which); <b>429</b> when the per-target attempt-lockout has tripped.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class EmailVerificationController {

    private final AuthFlowTenantResolver tenantResolver;
    private final EmailVerificationService emailVerificationService;
    private final UserService userService;

    @PostMapping(value = "/verify-email/request", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> requestVerification(
            @RequestParam("email") String email,
            @RequestParam("client_id") String clientId) {
        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        if (tenantId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        TenantContext ctx = TenantContext.of(tenantId.get());
        // FE-2d: resolved here so the emailed link can be tenant-namespaced (BE-B convention) — same call every
        // other tenant-namespacing site already makes.
        String tenantSlug = tenantResolver.resolveTenantSlug(clientId).orElse(null);
        // Resolve the user to associate the code with; if the email isn't a user, do nothing (uniform 200).
        if (userService.existsByEmail(ctx, email)) {
            UserView user = userService.getUserByEmail(ctx, email);
            emailVerificationService.requestVerification(ctx, user.id(), email, clientId, tenantSlug);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/verify-email/confirm", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> confirm(
            @RequestParam("email") String email,
            @RequestParam("code") String code,
            @RequestParam("client_id") String clientId) {
        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        if (tenantId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        VerificationOutcome outcome = emailVerificationService.verify(TenantContext.of(tenantId.get()), email, code);
        return switch (outcome) {
            // ALREADY_USED reads as success to the caller (see class javadoc) — the service already made sure
            // there's nothing left to act on for it, so there's nothing more to do here either.
            case VERIFIED, ALREADY_USED -> ResponseEntity.ok().build();
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).build();
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            case INVALID -> ResponseEntity.badRequest().build(); // generic — no further enumeration
        };
    }
}

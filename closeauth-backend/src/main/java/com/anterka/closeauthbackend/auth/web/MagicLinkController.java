package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.enums.AuthMethod;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.MagicLinkService;
import com.anterka.closeauthbackend.auth.service.MagicLinkService.MagicLinkAuthResult;
import com.anterka.closeauthbackend.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Magic-link login endpoints (Stage 6b-i). Tenant-scoped via {@code client_id}.
 *
 * <h2>HTTP contract</h2>
 * <ul>
 *   <li>{@code POST /magic-link/request} (form: {@code email}, {@code client_id}) → <b>200</b> always
 *       (enumeration-safe: identical whether or not the email exists; a link is emailed only if it does).</li>
 *   <li>{@code GET /magic-link/consume?token=...&client_id=...} (the emailed link) → on success establishes the
 *       session (amr=magic_link) and <b>302</b> resumes the OAuth flow (same completion as password login); on an
 *       invalid/expired link or a disallowed login, <b>302</b> to {@code /login}.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class MagicLinkController {

    private final AuthFlowTenantResolver tenantResolver;
    private final MagicLinkService magicLinkService;
    private final LoginSuccessResponder loginSuccessResponder;

    @PostMapping(value = "/magic-link/request", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> requestMagicLink(
            @RequestParam("email") String email,
            @RequestParam("client_id") String clientId) {
        tenantResolver.resolveTenantId(clientId).ifPresent(tenantId ->
                magicLinkService.requestMagicLink(TenantContext.of(tenantId), email, clientId));
        // Always 200 — never reveal whether the email exists or the client is valid.
        return ResponseEntity.ok().build();
    }

    @GetMapping("/magic-link/consume")
    public ResponseEntity<Void> consume(
            @RequestParam("token") String token,
            @RequestParam("client_id") String clientId,
            HttpServletRequest request,
            HttpServletResponse response) {
        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        if (tenantId.isEmpty()) {
            return redirectTo("/login");
        }
        MagicLinkAuthResult result = magicLinkService.consume(token, tenantId.get());
        if (!result.authenticated()) {
            return redirectTo("/login"); // invalid/expired link, or login not allowed
        }
        String redirectUrl = loginSuccessResponder.establishSessionAndResolveRedirect(
                request, response, result.tenantId(), result.userId(), AuthMethod.MAGIC_LINK.amrValue(), false);
        return redirectTo(redirectUrl);
    }

    private ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}

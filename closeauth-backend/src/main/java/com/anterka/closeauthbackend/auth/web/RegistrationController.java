package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.dto.RegisterUserCommand;
import com.anterka.closeauthbackend.auth.dto.RegistrationResult;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.RegistrationService;
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
 * Self-registration endpoint (Stage 6b-i). Tenant-scoped: the tenant is resolved from the authorization request's
 * {@code client_id}; the tenant's registration mode selects the strategy.
 *
 * <h2>HTTP contract</h2>
 * {@code POST /register} (form: {@code email}, {@code password}, {@code first_name?}, {@code last_name?},
 * {@code phone?}, {@code client_id}, {@code invite_token?}) →
 * <ul>
 *   <li><b>200</b> {@code {userId, status, mode, emailVerificationSent}} — the UI uses {@code status}/{@code mode} to
 *       decide the next screen (verify email, await approval, or proceed to login).</li>
 *   <li><b>409</b> if the email is already registered in the tenant; <b>403</b> if invite-only and the invite is
 *       missing/invalid; <b>400</b> on validation errors (mapped by {@code AuthDomainExceptionAdvice}).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class RegistrationController {

    private final AuthFlowTenantResolver tenantResolver;
    private final RegistrationService registrationService;

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<RegistrationResult> register(
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam(value = "first_name", required = false) String firstName,
            @RequestParam(value = "last_name", required = false) String lastName,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam("client_id") String clientId,
            @RequestParam(value = "invite_token", required = false) String inviteToken) {

        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        if (tenantId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        // FE-2d: resolved here (not inside a strategy) so EmailVerifiedRegistrationStrategy can build a
        // tenant-namespaced verification link without widening RegistrationStrategy's shared interface.
        String tenantSlug = tenantResolver.resolveTenantSlug(clientId).orElse(null);
        RegistrationResult result = registrationService.register(TenantContext.of(tenantId.get()),
                new RegisterUserCommand(email, password, firstName, lastName, phone, inviteToken, clientId, tenantSlug));
        return ResponseEntity.ok(result);
    }
}

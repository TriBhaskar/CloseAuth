package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.common.web.ProblemDetails;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminAuthResult;
import com.anterka.closeauthbackend.platform.service.PlatformAdminService;
import com.anterka.closeauthbackend.platform.service.PlatformAdminTokenService;
import com.anterka.closeauthbackend.platform.service.PlatformAdminTokenService.MintedToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The platform-admin token endpoint (§7.8, Stage 7a) — the platform-admin auth path. Authenticates a platform admin
 * (enumeration-safe) and mints the platform-admin access token (sub=admin UUID, platform roles, NO tenant_id). This is
 * the ONE non-bearer {@code /v1} endpoint (permitAll on the admin chain); every other {@code /v1} endpoint requires the
 * token this issues.
 *
 * <h2>HTTP contract</h2>
 * {@code POST /v1/platform/auth/token} (JSON {@code {email, password}}) → 200 {@code {access_token, token_type:"Bearer",
 * expires_in}}; on failure a uniform RFC 7807 <b>401</b> (never reveals whether the admin exists / which factor failed).
 */
@RestController
@RequiredArgsConstructor
public class PlatformAuthController {

    private final PlatformAdminService platformAdminService;
    private final PlatformAdminTokenService platformAdminTokenService;

    @PostMapping(value = "/v1/platform/auth/token",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> token(@Valid @RequestBody PlatformTokenRequest request, HttpServletRequest http) {
        PlatformAdminAuthResult result = platformAdminService.authenticate(request.email(), request.password());
        if (!result.success()) {
            // Uniform, enumeration-safe RFC 7807 401 — identical for unknown/suspended admin or wrong password.
            ProblemDetail problem = ProblemDetails.of(HttpStatus.UNAUTHORIZED, "invalid_credentials",
                    "Invalid credentials.", http.getRequestURI());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }
        MintedToken token = platformAdminTokenService.mintFor(result.adminId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", token.expiresInSeconds());
        return ResponseEntity.ok(body);
    }

    /** Platform-admin credential request. */
    public record PlatformTokenRequest(@NotBlank String email, @NotBlank String password) {
    }
}

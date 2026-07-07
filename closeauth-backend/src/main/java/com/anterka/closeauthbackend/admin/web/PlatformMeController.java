package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresPlatformAdmin;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.service.PlatformAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The 7a demonstration endpoint — proves the platform-admin token path + the platform-scoped authorization gate end to
 * end. NOT part of the CRUD surface (that is 7b); it just returns the caller's own platform-admin identity.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/platform/me} (bearer platform-admin token) → 200 {@code {sub, email, status, roles}}. Requires
 * {@code PLATFORM_ADMIN} ({@link RequiresPlatformAdmin}); a tenant-admin token → 403 (RFC 7807); no token → 401.
 */
@RestController
@RequiredArgsConstructor
public class PlatformMeController {

    private final PlatformAdminService platformAdminService;

    @GetMapping(value = "/v1/platform/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPlatformAdmin
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal Jwt jwt) {
        UUID adminId = UUID.fromString(jwt.getSubject());
        PlatformAdminView admin = platformAdminService.getById(adminId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", admin.id().toString());
        body.put("email", admin.email());
        body.put("status", admin.status().name());
        body.put("roles", platformAdminService.resolveRoleNames(adminId));
        return ResponseEntity.ok(body);
    }
}

package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 7a demonstration endpoint for the tenant-scoped authorization gate — proves the cross-tenant admin guard. NOT
 * part of the CRUD surface (7b); it just confirms the caller may administer the path tenant.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/tenants/{tenantId}/admin-ping} (bearer token) → 200 {@code {ok, tenantId}}. Requires
 * {@link RequiresTenantAccess}: a platform admin (any tenant), OR a {@code TENANT_ADMIN} whose token tenant matches
 * {@code {tenantId}}. A tenant admin of A calling {@code /v1/tenants/{B}/...} → 403 (the cross-tenant admin guard).
 * The path variable MUST be named {@code tenantId} (bound by the annotation's SpEL).
 */
@RestController
public class TenantAdminPingController {

    @GetMapping(value = "/v1/tenants/{tenantId}/admin-ping", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresTenantAccess
    public ResponseEntity<Map<String, Object>> ping(@PathVariable String tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("tenantId", tenantId);
        return ResponseEntity.ok(body);
    }
}

package com.anterka.closeauthbackend.audit.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.audit.dto.AuditEventView;
import com.anterka.closeauthbackend.audit.service.AuditQueryService;
import com.anterka.closeauthbackend.common.web.PageView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The customer-facing audit query API (§7.11), from MVP. {@link RequiresTenantAccess} enforces platform-admin-OR-
 * {@code TENANT_ADMIN}-of-this-tenant (the 7a cross-tenant guard). Tenant-scoping is enforced BOTH at the gate AND at
 * the query layer (the path {@code tenantId} is always applied as a filter in {@link AuditQueryService}) — a tenant's
 * audit query can never return another tenant's events even if the gate were somehow bypassed, per §7.11's "no
 * exceptions" rule. Cross-tenant queries are a separate, {@code @RequiresPlatformAdmin} endpoint
 * ({@code PlatformAuditController}).
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/tenants/{tenantId}/audit-events?event_type&from&to&user_id&client_id&actor&page&size} →
 * {@link PageView} of {@link AuditEventView}, newest-first. Filters are optional; {@code from}/{@code to} are ISO-8601
 * ({@code to} exclusive). Bulk export (CSV/JSON) is Phase 2 — not built.
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}")
public class TenantAuditController {

    private final AuditQueryService auditQueryService;

    @GetMapping("/audit-events")
    public PageView<AuditEventView> list(@PathVariable String tenantId,
                                         @RequestParam(name = "event_type", required = false) String eventType,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(name = "user_id", required = false) String userId,
                                         @RequestParam(name = "client_id", required = false) String clientId,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return auditQueryService.search(
                UUID.fromString(tenantId),
                AuditQueryParams.eventType(eventType),
                AuditQueryParams.instant("from", from),
                AuditQueryParams.instant("to", to),
                AuditQueryParams.uuid("user_id", userId),
                clientId,
                AuditQueryParams.uuid("actor", actor),
                page, size);
    }
}

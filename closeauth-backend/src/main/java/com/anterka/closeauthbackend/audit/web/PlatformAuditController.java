package com.anterka.closeauthbackend.audit.web;

import com.anterka.closeauthbackend.admin.security.RequiresPlatformAdmin;
import com.anterka.closeauthbackend.audit.dto.AuditEventView;
import com.anterka.closeauthbackend.audit.service.AuditQueryService;
import com.anterka.closeauthbackend.common.web.PageView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The platform-admin cross-tenant audit endpoint (§7.11): "platform-admin cross-tenant queries do so through a
 * different endpoint with explicit cross-tenant scope." {@link RequiresPlatformAdmin}-gated — a tenant admin can never
 * reach it. Unlike the tenant endpoint, {@code tenant_id} here is an OPTIONAL filter: omitted → across ALL tenants;
 * supplied → scoped to that one tenant. This is the ONLY path that can read across tenant boundaries.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/platform/audit-events?tenant_id&event_type&from&to&user_id&client_id&actor&page&size} →
 * {@link PageView} of {@link AuditEventView}, newest-first.
 */
@RestController
@RequiredArgsConstructor
@RequiresPlatformAdmin
@RequestMapping("/v1/platform")
public class PlatformAuditController {

    private final AuditQueryService auditQueryService;

    @GetMapping("/audit-events")
    public PageView<AuditEventView> list(@RequestParam(name = "tenant_id", required = false) String tenantId,
                                         @RequestParam(name = "event_type", required = false) String eventType,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(name = "user_id", required = false) String userId,
                                         @RequestParam(name = "client_id", required = false) String clientId,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        UUID tenant = AuditQueryParams.uuid("tenant_id", tenantId); // null → all tenants (explicit cross-tenant scope)
        return auditQueryService.search(
                tenant,
                AuditQueryParams.eventType(eventType),
                AuditQueryParams.instant("from", from),
                AuditQueryParams.instant("to", to),
                AuditQueryParams.uuid("user_id", userId),
                clientId,
                AuditQueryParams.uuid("actor", actor),
                page, size);
    }
}

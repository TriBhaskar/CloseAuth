package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * The filter criteria for an audit-event search (§7.11 query API). Every field is optional (null = no filter) EXCEPT
 * as governed by the caller:
 *
 * <ul>
 *   <li>The tenant-scoped endpoint ALWAYS sets {@link #tenantId} — tenant-scoping is enforced at the query layer with
 *       no exceptions, so a tenant can never see another tenant's events even if the gate were bypassed.</li>
 *   <li>The platform endpoint may leave {@link #tenantId} null (all tenants) or set it (a specific tenant) — explicit
 *       cross-tenant scope, reachable only through the {@code @RequiresPlatformAdmin} path.</li>
 * </ul>
 *
 * {@code offset}/{@code limit} are already clamped by the caller (via {@code PageView} bounds).
 */
public record AuditEventQuery(
        UUID tenantId,
        AuditEventType eventType,
        Instant from,
        Instant to,
        UUID subjectUserId,
        String actorClientRegisteredId,
        UUID actorUserId,
        int offset,
        int limit) {
}

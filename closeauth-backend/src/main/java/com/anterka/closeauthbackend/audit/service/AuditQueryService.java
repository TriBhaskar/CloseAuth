package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.dto.AuditEventView;
import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.repository.AuditEventQuery;
import com.anterka.closeauthbackend.audit.repository.AuditEventRepository;
import com.anterka.closeauthbackend.common.web.PageView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read side of the audit product (§7.11), shared by the tenant-scoped and platform endpoints. Clamps paging to the
 * same bounds as every other admin list ({@link PageView}), runs the filtered page + total-count against the read-only
 * query fragment, and maps to {@link AuditEventView}. Tenant-scoping is the caller's contract: the tenant endpoint
 * passes a non-null {@code tenantId} (always filtered here AND at the gate); the platform endpoint may pass null (all
 * tenants) or a specific tenant — the only path that can, being {@code @RequiresPlatformAdmin}-gated.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditEventRepository repository;

    public PageView<AuditEventView> search(UUID tenantId, AuditEventType eventType, Instant from, Instant to,
                                           UUID userId, String clientId, UUID actor, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), PageView.MAX_SIZE);
        int safePage = Math.max(page, 0);

        AuditEventQuery query = new AuditEventQuery(
                tenantId, eventType, from, to, userId, clientId, actor, safePage * safeSize, safeSize);

        long total = repository.count(query);
        List<AuditEventView> items = repository.search(query).stream().map(AuditEventView::from).toList();
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new PageView<>(items, safePage, safeSize, total, totalPages);
    }
}

package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;

import java.util.List;

/**
 * Read-only custom fragment for the audit query API (§7.11) — dynamic filtering without exposing a
 * {@code JpaSpecificationExecutor} (whose surface includes {@code delete(Specification)}). Keeping this a hand-rolled,
 * read-only fragment is part of the append-only enforcement: {@link AuditEventRepository} exposes only INSERT + reads.
 */
public interface AuditEventQueryRepository {

    /** Newest-first page of events matching {@code query} (tenant-scoping applied inside — always). */
    List<AuditEvent> search(AuditEventQuery query);

    /** Total number of events matching {@code query} (for pagination totals). */
    long count(AuditEventQuery query);
}

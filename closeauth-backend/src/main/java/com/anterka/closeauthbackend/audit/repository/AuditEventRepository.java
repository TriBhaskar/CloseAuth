package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Append-only repository for the {@link AuditEvent} aggregate (§7.11). The immutability guarantee is enforced at the
 * interface: this extends the bare {@link Repository} marker (NOT {@code JpaRepository}/{@code CrudRepository}), so
 * the ONLY mutating operation is {@link #save} — which, because {@code AuditEvent}'s id is DB-generated and never
 * re-used, is always an {@code INSERT}. There is no {@code delete*}/{@code remove*}/{@code update*} method: application
 * code cannot tamper with the log through this repository even if compromised. (A DB-role INSERT-only grant for the
 * writer role is the complementary deploy-time hardening — see STAGE_8_REPORT.md.)
 *
 * <p>Rich dynamic reads for the customer query API come from {@link AuditEventQueryRepository} (a read-only fragment).
 * Retention deletion is a deliberately separate, operational path ({@code AuditRetentionRepository}) — not part of
 * this customer/write surface.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID>, AuditEventQueryRepository {

    /** Append a new audit event. INSERT-only in practice (id is DB-generated; rows are never updated). */
    AuditEvent save(AuditEvent event);

    /** Read a single event by id (used by tests and by internal retrieval; never mutates). */
    Optional<AuditEvent> findById(UUID id);

    /** Total row count (read-only; used by tests/metrics). */
    long count();
}

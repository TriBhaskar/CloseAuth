package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the {@link AuditOutbox} aggregate root. The outbox worker drains unprocessed rows oldest-first
 * (§7.11), skipping rows that have exhausted their retry budget (dead-lettered). Deliberately not tenant-scoped — the
 * worker drains across all tenants. The undrained query is backed by the partial index
 * {@code idx_audit_outbox_unprocessed}.
 */
@Repository
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, UUID> {

    /** All undrained rows oldest-first (includes dead-lettered) — used by tests. */
    List<AuditOutbox> findByProcessedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    /**
     * The worker's batch: undrained rows that still have retry budget ({@code attempts < maxAttempts}), oldest first.
     * Dead-lettered rows (attempts exhausted) are excluded so the worker never tight-loops on a poison row.
     */
    @Query("""
            select o from AuditOutbox o
             where o.processedAt is null and o.attempts < :maxAttempts
             order by o.createdAt asc""")
    List<AuditOutbox> findDrainable(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    /** Undrained rows still within retry budget — the operational "backlog depth" signal. */
    long countByProcessedAtIsNullAndAttemptsLessThan(int maxAttempts);

    /** Rows that exhausted their retry budget and were never drained — the dead-letter depth signal. */
    long countByProcessedAtIsNullAndAttemptsGreaterThanEqual(int maxAttempts);

    /** Creation time of the oldest undrained (still-retryable) row, or null if none — feeds the oldest-age metric. */
    @Query("""
            select min(o.createdAt) from AuditOutbox o
             where o.processedAt is null and o.attempts < :maxAttempts""")
    Instant oldestUndrainedCreatedAt(@Param("maxAttempts") int maxAttempts);
}

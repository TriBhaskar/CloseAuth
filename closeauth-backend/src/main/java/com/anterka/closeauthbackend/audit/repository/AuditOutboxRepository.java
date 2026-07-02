package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the {@link AuditOutbox} aggregate root. The outbox worker drains
 * unprocessed rows oldest-first (Section 7.11); this query is backed by the partial
 * index {@code idx_audit_outbox_unprocessed}. Deliberately not tenant-scoped — the
 * worker drains across all tenants.
 */
@Repository
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, UUID> {

    List<AuditOutbox> findByProcessedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}

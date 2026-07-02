package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for the {@link AuditEvent} aggregate root (append-only). The customer
 * audit query API is tenant-scoped with no exceptions (Section 7.11); cross-tenant
 * platform queries go through a separate, explicitly platform-scoped path added later.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<AuditEvent> findByTenantIdAndSubjectUserIdOrderByCreatedAtDesc(
            UUID tenantId, UUID subjectUserId, Pageable pageable);
}

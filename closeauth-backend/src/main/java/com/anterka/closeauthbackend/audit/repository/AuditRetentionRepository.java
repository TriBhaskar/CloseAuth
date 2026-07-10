package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * The retention mechanism's ONLY delete path (§7.11). Deliberately separate from {@link AuditEventRepository} (which
 * stays append-only for the customer/write surface): retention is an operational concern, not something the audit
 * write path should ever reach. This bulk-deletes {@code audit_events} rows older than the retention window — it never
 * touches the rows those events reference, so it does not conflict with Stage 1's {@code ON DELETE RESTRICT} FKs
 * (deleting old audit rows only FREES the restrict; it does not hard-delete users/tenants/clients).
 */
public interface AuditRetentionRepository extends Repository<AuditEvent, UUID> {

    /** Deletes audit events created strictly before {@code cutoff}. Returns the number of rows removed. */
    @Modifying
    @Query("delete from AuditEvent a where a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

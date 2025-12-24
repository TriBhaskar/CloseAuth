package com.anterka.closeauthbackend.audit.repository;

import com.anterka.closeauthbackend.audit.entity.AuditLogs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogs, Long> {

    /**
     * Find all audit logs for a specific client
     */
    List<AuditLogs> findByClient_IdOrderByCreatedAtDesc(String clientId);

    /**
     * Find all audit logs for a specific user
     */
    List<AuditLogs> findByUser_IdOrderByCreatedAtDesc(Integer userId);

    /**
     * Find audit logs by action type
     */
    List<AuditLogs> findByActionOrderByCreatedAtDesc(String action);

    /**
     * Find audit logs for a client within a date range
     */
    List<AuditLogs> findByClient_IdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String clientId, LocalDateTime start, LocalDateTime end);

    /**
     * Find failed operations only
     */
    List<AuditLogs> findBySuccessFalseOrderByCreatedAtDesc();
}


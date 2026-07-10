package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.repository.AuditRetentionRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled retention cleanup (§7.11): deletes {@code audit_events} older than the configured hot-retention window
 * (default 90 days, config-driven). <b>Opt-in</b> ({@code closeauth.audit.retention.enabled=false} by default) — the
 * mechanism ships for MVP but does not silently delete history until an operator turns it on; per-tenant retention is
 * Phase 2 and the config value is the clean landing spot for it.
 *
 * <p>It deletes ONLY {@code audit_events} rows (via the dedicated {@link AuditRetentionRepository}) — never the
 * users/tenants/clients those rows reference. So it does not conflict with Stage 1's {@code ON DELETE RESTRICT} FKs;
 * removing old audit rows only relaxes the restrict.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRetentionJob {

    private final AuditRetentionRepository retentionRepository;
    private final CloseAuthProperties properties;

    @Scheduled(cron = "${closeauth.audit.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int deleted = runIfEnabled();
        if (deleted >= 0) {
            log.info("Audit retention cleanup removed {} expired audit_events row(s)", deleted);
        }
    }

    /** Runs the purge if enabled and returns rows deleted; returns -1 (a no-op signal) when retention is disabled. */
    int runIfEnabled() {
        CloseAuthProperties.Audit.Retention retention = properties.getAudit().getRetention();
        if (!retention.isEnabled()) {
            log.debug("Audit retention cleanup skipped (disabled). Policy window = {} days.", retention.getDays());
            return -1;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retention.getDays()));
        return retentionRepository.deleteOlderThan(cutoff);
    }
}

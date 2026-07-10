package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.repository.AuditRetentionRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retention policy behavior (§7.11): disabled by default (MVP ships the mechanism, not automated deletion); when
 * enabled, deletes only {@code audit_events} older than the config-driven window.
 */
class AuditRetentionJobTest {

    private final AuditRetentionRepository retentionRepository = mock(AuditRetentionRepository.class);
    private final CloseAuthProperties properties = new CloseAuthProperties();
    private final AuditRetentionJob job = new AuditRetentionJob(retentionRepository, properties);

    @Test
    void disabledByDefaultDoesNotDelete() {
        assertThat(properties.getAudit().getRetention().isEnabled()).isFalse(); // MVP default
        int result = job.runIfEnabled();
        assertThat(result).isEqualTo(-1); // no-op signal
        verify(retentionRepository, never()).deleteOlderThan(any());
    }

    @Test
    void whenEnabledDeletesEventsOlderThanTheConfiguredWindow() {
        properties.getAudit().getRetention().setEnabled(true);
        properties.getAudit().getRetention().setDays(90);
        when(retentionRepository.deleteOlderThan(any())).thenReturn(7);

        Instant before = Instant.now();
        int deleted = job.runIfEnabled();
        Instant after = Instant.now();

        assertThat(deleted).isEqualTo(7);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(retentionRepository).deleteOlderThan(cutoff.capture());
        // Cutoff is ~90 days before "now" (within the test's wall-clock window).
        assertThat(cutoff.getValue()).isBetween(before.minus(Duration.ofDays(90)), after.minus(Duration.ofDays(90)));
    }
}

package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.repository.AuditOutboxRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Outbox health as first-class operational signals (§16.5 — Outbox Reliability). Exposes, via Micrometer:
 * <ul>
 *   <li>{@code closeauth.audit.outbox.undrained} (gauge) — backlog depth: undrained rows still within retry budget.</li>
 *   <li>{@code closeauth.audit.outbox.dead_lettered} (gauge) — rows that exhausted retries and were never drained.</li>
 *   <li>{@code closeauth.audit.outbox.oldest_age_seconds} (gauge) — age of the oldest undrained row (drain lag).</li>
 *   <li>{@code closeauth.audit.outbox.drained} (counter) — total rows successfully drained.</li>
 *   <li>{@code closeauth.audit.outbox.drain_failures} (counter) — total drain attempts that failed.</li>
 * </ul>
 * Gauges are polled on scrape (cheap count queries); not a dashboard — just the numbers, per the stage brief.
 */
@Component
public class AuditOutboxMetrics {

    private final AuditOutboxRepository outboxRepository;
    private final int maxAttempts;
    private final Counter drainedCounter;
    private final Counter drainFailureCounter;

    public AuditOutboxMetrics(MeterRegistry meterRegistry,
                              AuditOutboxRepository outboxRepository,
                              CloseAuthProperties properties) {
        this.outboxRepository = outboxRepository;
        this.maxAttempts = properties.getAudit().getMaxAttempts();

        Gauge.builder("closeauth.audit.outbox.undrained", this, AuditOutboxMetrics::undrainedDepth)
                .description("Undrained audit outbox rows still within retry budget (backlog depth)")
                .register(meterRegistry);
        Gauge.builder("closeauth.audit.outbox.dead_lettered", this, AuditOutboxMetrics::deadLetteredDepth)
                .description("Audit outbox rows that exhausted their retry budget and were never drained")
                .register(meterRegistry);
        Gauge.builder("closeauth.audit.outbox.oldest_age_seconds", this, AuditOutboxMetrics::oldestUndrainedAgeSeconds)
                .description("Age in seconds of the oldest undrained audit outbox row (drain lag)")
                .register(meterRegistry);
        this.drainedCounter = Counter.builder("closeauth.audit.outbox.drained")
                .description("Audit outbox rows successfully drained to audit_events")
                .register(meterRegistry);
        this.drainFailureCounter = Counter.builder("closeauth.audit.outbox.drain_failures")
                .description("Audit outbox drain attempts that failed")
                .register(meterRegistry);
    }

    public void recordDrained() {
        drainedCounter.increment();
    }

    public void recordFailure() {
        drainFailureCounter.increment();
    }

    double undrainedDepth() {
        return outboxRepository.countByProcessedAtIsNullAndAttemptsLessThan(maxAttempts);
    }

    double deadLetteredDepth() {
        return outboxRepository.countByProcessedAtIsNullAndAttemptsGreaterThanEqual(maxAttempts);
    }

    double oldestUndrainedAgeSeconds() {
        Instant oldest = outboxRepository.oldestUndrainedCreatedAt(maxAttempts);
        return oldest == null ? 0d : Math.max(0d, Duration.between(oldest, Instant.now()).toMillis() / 1000d);
    }
}

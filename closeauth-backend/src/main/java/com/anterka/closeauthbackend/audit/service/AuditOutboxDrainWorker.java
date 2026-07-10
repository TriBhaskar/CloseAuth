package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import com.anterka.closeauthbackend.audit.repository.AuditOutboxRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The async drain worker (§7.11): on a fixed schedule, sweeps undrained {@code audit_outbox} rows to
 * {@code audit_events}, decoupled from request latency. If the worker is slow or briefly down, rows simply wait — the
 * outbox write already committed with the business action, so nothing is lost.
 *
 * <p><b>Reliability:</b> each row is drained in its own transaction ({@link AuditOutboxProcessor}); a row that fails to
 * drain (e.g. a transient DB blip) has its {@code attempts}/{@code last_error} bumped and is retried next tick. Once a
 * row exhausts {@code maxAttempts} it is dead-lettered: left in place, logged loudly, and excluded from future batches
 * (via {@link AuditOutboxRepository#findDrainable}) so the worker never tight-loops on a poison row. Its depth is a
 * metric ({@link AuditOutboxMetrics}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditOutboxDrainWorker {

    private final AuditOutboxRepository outboxRepository;
    private final AuditOutboxProcessor processor;
    private final AuditOutboxMetrics metrics;
    private final CloseAuthProperties properties;

    @Scheduled(fixedDelayString = "${closeauth.audit.drain-interval-ms:2000}")
    public void drain() {
        drainOnce();
    }

    /**
     * Drains up to one batch and returns the number of rows successfully drained. Exposed (not just {@link #drain})
     * so tests/integration can force a deterministic drain without waiting on the scheduler.
     */
    public int drainOnce() {
        int maxAttempts = properties.getAudit().getMaxAttempts();
        int batchSize = properties.getAudit().getDrainBatchSize();
        List<AuditOutbox> batch = outboxRepository.findDrainable(maxAttempts, PageRequest.of(0, batchSize));

        int drained = 0;
        for (AuditOutbox row : batch) {
            try {
                processor.process(row.getId());
                metrics.recordDrained();
                drained++;
            } catch (RuntimeException failure) {
                metrics.recordFailure();
                processor.recordFailure(row.getId(), failure.toString());
                int attempts = row.getAttempts() + 1;
                if (attempts >= maxAttempts) {
                    log.error("Audit outbox row {} DEAD-LETTERED after {} attempts (excluded from future drains): {}",
                            row.getId(), attempts, failure.toString());
                } else {
                    log.warn("Audit outbox row {} drain failed (attempt {}/{}); will retry: {}",
                            row.getId(), attempts, maxAttempts, failure.toString());
                }
            }
        }
        return drained;
    }
}

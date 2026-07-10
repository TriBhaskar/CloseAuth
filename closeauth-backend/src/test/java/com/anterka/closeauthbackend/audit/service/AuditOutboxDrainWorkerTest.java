package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import com.anterka.closeauthbackend.audit.repository.AuditOutboxRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drain loop's reliability behavior (§7.11): a drainable batch is processed row-by-row; a row that fails to drain
 * does NOT abort the batch — it is counted as a failure and its retry counter bumped (via
 * {@link AuditOutboxProcessor#recordFailure}) so it retries next tick rather than being silently dropped.
 */
class AuditOutboxDrainWorkerTest {

    private final AuditOutboxRepository outboxRepository = mock(AuditOutboxRepository.class);
    private final AuditOutboxProcessor processor = mock(AuditOutboxProcessor.class);
    private final AuditOutboxMetrics metrics = mock(AuditOutboxMetrics.class);
    private final CloseAuthProperties properties = new CloseAuthProperties();
    private final AuditOutboxDrainWorker worker =
            new AuditOutboxDrainWorker(outboxRepository, processor, metrics, properties);

    private AuditOutbox row(int attempts) {
        AuditOutbox r = new AuditOutbox();
        r.setId(UUID.randomUUID());
        r.setAttempts(attempts);
        return r;
    }

    @Test
    void drainsEveryRowInTheBatchAndCountsSuccesses() {
        AuditOutbox a = row(0);
        AuditOutbox b = row(0);
        when(outboxRepository.findDrainable(anyInt(), any(Pageable.class))).thenReturn(List.of(a, b));
        doNothing().when(processor).process(any(UUID.class));

        int drained = worker.drainOnce();

        assertThat(drained).isEqualTo(2);
        verify(processor).process(a.getId());
        verify(processor).process(b.getId());
        verify(metrics, org.mockito.Mockito.times(2)).recordDrained();
    }

    @Test
    void aFailingRowIsRetriedNotDroppedAndDoesNotAbortTheBatch() {
        AuditOutbox poison = row(1);
        AuditOutbox healthy = row(0);
        when(outboxRepository.findDrainable(anyInt(), any(Pageable.class))).thenReturn(List.of(poison, healthy));
        doThrow(new RuntimeException("transient DB blip")).when(processor).process(poison.getId());
        doNothing().when(processor).process(healthy.getId());

        int drained = worker.drainOnce();

        assertThat(drained).isEqualTo(1); // only the healthy row counted as drained
        verify(processor).recordFailure(eq(poison.getId()), any(String.class)); // poison bumped for retry, not dropped
        verify(processor).process(healthy.getId());                             // batch continued past the poison row
        verify(metrics).recordFailure();
        verify(metrics).recordDrained();
    }
}

package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import com.anterka.closeauthbackend.audit.event.AuditEventPayload;
import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent;
import com.anterka.closeauthbackend.audit.repository.AuditOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The outbox write half of the pipeline: a published {@link CloseAuthAuditEvent} becomes an {@code audit_outbox} row
 * whose {@code tenant_id} is denormalized and whose {@code event_payload} JSON round-trips back to the same event
 * shape (so the drain worker can reconstruct the {@code audit_events} row from the outbox row alone).
 */
class AuditOutboxWriterTest {

    private final AuditOutboxRepository outboxRepository = mock(AuditOutboxRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditOutboxWriter writer = new AuditOutboxWriter(outboxRepository, objectMapper);

    @Test
    void writesOutboxRowWithDenormalizedTenantAndRoundTrippablePayload() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        CloseAuthAuditEvent event = AuditEvents.userSuspended(tenant, user);

        writer.write(event);

        ArgumentCaptor<AuditOutbox> captor = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outboxRepository).save(captor.capture());
        AuditOutbox row = captor.getValue();

        assertThat(row.getTenantId()).isEqualTo(tenant);
        AuditEventPayload payload = objectMapper.readValue(row.getEventPayload(), AuditEventPayload.class);
        assertThat(payload.eventType()).isEqualTo(event.getEventType());
        assertThat(payload.tenantId()).isEqualTo(tenant);
        assertThat(payload.subjectUserId()).isEqualTo(user);
        assertThat(payload.outcome()).isEqualTo(event.getOutcome());
    }
}

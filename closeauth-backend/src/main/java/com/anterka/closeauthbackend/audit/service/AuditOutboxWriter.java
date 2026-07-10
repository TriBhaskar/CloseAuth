package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import com.anterka.closeauthbackend.audit.event.AuditEventPayload;
import com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent;
import com.anterka.closeauthbackend.audit.repository.AuditOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes a published {@link CloseAuthAuditEvent} to {@code audit_outbox} — the durable half of the outbox pattern
 * (§7.11). An async worker later drains the outbox to {@code audit_events}.
 *
 * <h2>Why {@code BEFORE_COMMIT} — the atomicity guarantee</h2>
 * The listener fires while the publishing transaction is still open, so the outbox {@code INSERT} joins the SAME
 * database transaction as the business mutation that emitted the event. Both commit together or both roll back
 * together: an audit intent is never lost after its business action commits, and never recorded if the business
 * action rolls back. {@code AFTER_COMMIT} would NOT give this — the business tx would already be committed when the
 * listener ran, so a failure writing the outbox would silently drop the event with no way to undo the business change.
 *
 * <h2>{@code fallbackExecution = true}</h2>
 * Some seams emit outside any transaction (a pure-read auth check: login failure, token introspection, logout with no
 * ledger row). With fallback enabled the listener still runs — synchronously, inline — and the {@code save} opens its
 * own short transaction. There is no business mutation to be atomic with in those cases, so immediate durable write is
 * exactly right.
 *
 * <p><b>Failure semantics:</b> a serialization/insert failure here propagates, rolling back the business action. That
 * is the deliberate cost of treating audit as tier-1: for the controlled, factory-produced payloads this path handles,
 * serialization cannot realistically fail, so the practical effect is durability, not fragility. Documented in
 * STAGE_8_REPORT.md.
 */
@Component
@RequiredArgsConstructor
public class AuditOutboxWriter {

    private final AuditOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onAuditEvent(CloseAuthAuditEvent event) {
        write(event);
    }

    /** The outbox {@code INSERT}. Package-private so unit tests can drive it directly, bypassing the listener plumbing. */
    void write(CloseAuthAuditEvent event) {
        AuditOutbox row = new AuditOutbox();
        row.setTenantId(event.getTenantId());
        row.setEventPayload(serialize(event));
        outboxRepository.save(row);
    }

    private String serialize(CloseAuthAuditEvent event) {
        try {
            return objectMapper.writeValueAsString(AuditEventPayload.from(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit event payload: " + event.getEventType(), e);
        }
    }
}

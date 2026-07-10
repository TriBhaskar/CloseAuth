package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The single entry point service code uses to record an audit event (§7.11). Enriches the event with request context
 * (IP / User-Agent / actor) and publishes it; {@code AuditOutboxWriter} then writes the outbox row inside the caller's
 * transaction. Call sites build the event with a typed {@code AuditEvents.*} factory and hand it here — nothing else.
 *
 * <pre>{@code
 *   auditEmitter.emit(AuditEvents.tenantSuspended(tenantId));
 * }</pre>
 *
 * <p>Emission is best-effort with respect to the caller's control flow: publishing never returns a value and the
 * caller does not branch on it. It IS, however, transactionally coupled — see {@code AuditOutboxWriter} for why an
 * outbox-write failure rolls the business action back (the deliberate atomicity guarantee).
 */
@Service
@RequiredArgsConstructor
public class AuditEmitter {

    private final ApplicationEventPublisher publisher;
    private final AuditRequestContext requestContext;

    public void emit(CloseAuthAuditEvent event) {
        publisher.publishEvent(requestContext.enrich(event));
    }
}

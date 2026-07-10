package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.entity.AuditEvent;
import com.anterka.closeauthbackend.audit.entity.AuditOutbox;
import com.anterka.closeauthbackend.audit.event.AuditEventPayload;
import com.anterka.closeauthbackend.audit.repository.AuditEventRepository;
import com.anterka.closeauthbackend.audit.repository.AuditOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Per-row drain of {@code audit_outbox} → {@code audit_events}, each in its OWN transaction so one poison row cannot
 * roll back a whole batch. Separated from {@code AuditOutboxDrainWorker} (which owns the schedule/loop) so the
 * transactional boundary is a real bean method — not a self-invocation that Spring's proxy would skip.
 */
@Component
@RequiredArgsConstructor
public class AuditOutboxProcessor {

    /** Cap on the persisted {@code last_error} so a huge stack string can't bloat the row. */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final AuditOutboxRepository outboxRepository;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Drains one outbox row: inserts the typed {@code audit_events} row and marks the outbox row processed — atomically.
     * Idempotent: a row already processed (e.g. a concurrent tick) is a no-op. Throws on failure so the worker can
     * count it and bump the retry counter.
     */
    @Transactional
    public void process(UUID outboxId) {
        AuditOutbox row = outboxRepository.findById(outboxId).orElse(null);
        if (row == null || row.getProcessedAt() != null) {
            return; // gone or already drained — nothing to do
        }
        AuditEvent event = toEntity(deserialize(row.getEventPayload()));
        auditEventRepository.save(event);
        row.setProcessedAt(Instant.now());
        outboxRepository.save(row);
    }

    /** Records a drain failure against the row: bumps {@code attempts} and stores {@code last_error} (own transaction). */
    @Transactional
    public void recordFailure(UUID outboxId, String error) {
        outboxRepository.findById(outboxId).ifPresent(row -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(truncate(error));
            outboxRepository.save(row);
        });
    }

    private AuditEvent toEntity(AuditEventPayload payload) {
        AuditEvent event = new AuditEvent();
        event.setEventType(payload.eventType());
        event.setOutcome(payload.outcome());
        event.setTenantId(payload.tenantId());
        event.setSubjectUserId(payload.subjectUserId());
        event.setActorUserId(payload.actorUserId());
        event.setActorPlatformAdminId(payload.actorPlatformAdminId());
        event.setActorClientRegisteredId(payload.actorClientRegisteredId());
        event.setActorAgentId(payload.actorAgentId());
        event.setGrantedConsentId(payload.grantedConsentId());
        event.setResourceServerId(payload.resourceServerId());
        event.setIpAddress(payload.ipAddress());
        event.setUserAgent(payload.userAgent());
        event.setErrorCode(payload.errorCode());
        event.setEventData(serializeData(payload.data()));
        return event;
    }

    private AuditEventPayload deserialize(String json) {
        try {
            return objectMapper.readValue(json, AuditEventPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed audit outbox payload", e);
        }
    }

    private String serializeData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit event_data", e);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}

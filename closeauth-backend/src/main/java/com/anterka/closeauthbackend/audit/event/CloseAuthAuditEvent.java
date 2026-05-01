package com.anterka.closeauthbackend.audit.event;

import java.util.Map;

/**
 * Domain event for audit logging.
 * Published by services via ApplicationEventPublisher.
 * Consumed by AuditLogService via @TransactionalEventListener.
 */
public record CloseAuthAuditEvent(
        String clientId,
        Integer userId,
        String action,
        String ipAddress,
        String userAgent,
        Map<String, Object> metadata,
        boolean success,
        String errorMessage
) {
    /**
     * Convenience factory for successful actions.
     */
    public static CloseAuthAuditEvent success(
            String clientId, Integer userId, String action,
            String ipAddress, String userAgent, Map<String, Object> metadata) {
        return new CloseAuthAuditEvent(clientId, userId, action, ipAddress, userAgent, metadata, true, null);
    }

    /**
     * Convenience factory for failed actions.
     */
    public static CloseAuthAuditEvent failure(
            String clientId, Integer userId, String action,
            String ipAddress, String userAgent, String errorMessage) {
        return new CloseAuthAuditEvent(clientId, userId, action, ipAddress, userAgent, null, false, errorMessage);
    }
}


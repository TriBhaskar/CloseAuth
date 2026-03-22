package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.client.entity.Client;
import com.anterka.closeauthbackend.client.repository.ClientRepository;
import com.anterka.closeauthbackend.audit.entity.AuditLogs;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.audit.repository.AuditLogRepository;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for logging audit events for compliance and debugging.
 * Logs all configuration changes with client, user, and metadata.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Log an action with full audit trail
     */
    @Transactional
    public void logAction(
            String clientId,
            Integer userId,
            String action,
            String ipAddress,
            String userAgent,
            Map<String, Object> metadata,
            boolean success,
            String errorMessage) {

        try {
            Client client = clientId != null ?
                    clientRepository.findByClientId(clientId).orElse(null) : null;
            Users user = userId != null ?
                    userRepository.findById(userId).orElse(null) : null;

            String metadataJson = null;
            if (metadata != null && !metadata.isEmpty()) {
                try {
                    metadataJson = objectMapper.writeValueAsString(metadata);
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize metadata to JSON: {}", e.getMessage());
                    metadataJson = metadata.toString();
                }
            }

            AuditLogs auditLog = AuditLogs.builder()
                    .client(client)
                    .user(user)
                    .action(action)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .metadata(metadataJson)
                    .success(success)
                    .errorMessage(errorMessage)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit log created: action={}, clientId={}, userId={}, success={}",
                    action, clientId, userId, success);

        } catch (Exception e) {
            // Don't fail the main operation if audit logging fails
            log.error("Failed to create audit log: {}", e.getMessage(), e);
        }
    }

    /**
     * Overloaded method for successful operations without error message
     */
    public void logAction(
            String clientId,
            Integer userId,
            String action,
            String ipAddress,
            String userAgent,
            Map<String, Object> metadata) {
        logAction(clientId, userId, action, ipAddress, userAgent, metadata, true, null);
    }

    /**
     * Overloaded method for operations without metadata
     */
    public void logAction(
            String clientId,
            Integer userId,
            String action,
            String ipAddress,
            String userAgent) {
        logAction(clientId, userId, action, ipAddress, userAgent, null, true, null);
    }
}


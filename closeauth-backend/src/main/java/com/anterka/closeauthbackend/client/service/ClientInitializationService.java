package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.entity.Client;
import com.anterka.closeauthbackend.client.repository.ClientRepository;
import com.anterka.closeauthbackend.client.entity.ClientOwnerShip;
import com.anterka.closeauthbackend.audit.service.AuditLogService;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.client.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for initializing default configurations for newly registered clients
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientInitializationService {

    private final ClientRepository clientRepository;
    private final ClientOwnershipRepository clientOwnershipRepository;
    private final UserRepository userRepository;
    private final ApplicationRoleService applicationRoleService;
    private final ApplicationRegistrationConfigService registrationConfigService;
    private final ClientThemeService clientThemeService;
    private final AuditLogService auditLogService;

    /**
     * Initialize a new client with ownership and default configurations
     */
    @Transactional
    public void initializeClient(String clientId, Integer userId, String ipAddress, String userAgent) {
        try {
            // Create client ownership
            createClientOwnership(clientId, userId);

            // Create default configurations
            createDefaultConfigurations(clientId, userId);

            // Audit log
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("clientId", clientId);
            metadata.put("userId", userId);
            metadata.put("defaultConfigsCreated", true);
            auditLogService.logAction(clientId, userId, "CLIENT_INITIALIZED",
                    ipAddress, userAgent, metadata);

            log.info("Client initialized successfully: {} for user: {}", clientId, userId);

        } catch (Exception e) {
            log.error("Failed to initialize client: {}", clientId, e);

            // Log failure
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("clientId", clientId);
            metadata.put("userId", userId);
            auditLogService.logAction(clientId, userId, "CLIENT_INITIALIZATION_FAILED",
                    ipAddress, userAgent, metadata, false, e.getMessage());

            throw new RuntimeException("Failed to initialize client", e);
        }
    }

    /**
     * Create client ownership record
     */
    private void createClientOwnership(String clientId, Integer userId) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new IllegalStateException("Client not found after save"));

        Users user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        ClientOwnerShip ownership = ClientOwnerShip.builder()
                .client(client)
                .user(user)
                .build();

        clientOwnershipRepository.save(ownership);
        log.info("Created ownership record for client: {} and user: {}", clientId, userId);
    }

    /**
     * Create default configurations for a new client
     */
    private void createDefaultConfigurations(String clientId, Integer userId) {
        // Create default registration config
        registrationConfigService.createDefaultConfig(clientId);
        log.info("Created default registration config for client: {}", clientId);

        // Create default theme
        clientThemeService.createDefaultTheme(clientId);
        log.info("Created default theme for client: {}", clientId);

        // Create default role and auto-assign to owner
        applicationRoleService.createDefaultRole(clientId, userId);
        log.info("Created default role for client: {} and assigned to user: {}", clientId, userId);
    }
}


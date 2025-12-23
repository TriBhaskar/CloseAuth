package com.anterka.closeauthbackend.service;

import com.anterka.closeauthbackend.core.entities.ClientThemes;
import com.anterka.closeauthbackend.core.entities.ThemeConfigurations;
import com.anterka.closeauthbackend.core.repository.ClientThemeRepository;
import com.anterka.closeauthbackend.core.repository.ThemeConfigurationRepository;
import com.anterka.closeauthbackend.dto.request.CreateThemeConfigurationDto;
import com.anterka.closeauthbackend.dto.response.ThemeConfigResponse;
import com.anterka.closeauthbackend.exception.ClientOwnershipException;
import com.anterka.closeauthbackend.exception.InvalidThemeConfigurationException;
import com.anterka.closeauthbackend.exception.ThemeNotFoundException;
import com.anterka.closeauthbackend.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.security.UserContextHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ThemeConfigurationService {

    private final ThemeConfigurationRepository configRepository;
    private final ClientThemeRepository themeRepository;
    private final ClientOwnershipRepository clientOwnershipRepository;
    private final AuditLogService auditLogService;

    /**
     * Extract user ID from request attributes (set by TwoLayerAuthenticationFilter)
     */
    private Integer getCurrentUserId(HttpServletRequest request) {
        return UserContextHelper.getUserId(request);
    }

    /**
     * Verify that the current user owns the client
     */
    private void verifyClientOwnership(String clientId, HttpServletRequest request) {
        Integer userId = getCurrentUserId(request);
        if (!clientOwnershipRepository.existsByClient_IdAndUser_Id(clientId, userId)) {
            log.warn("User {} attempted to access client {} without ownership", userId, clientId);
            throw new ClientOwnershipException("You do not have permission to modify this client");
        }
    }

    /**
     * Create a new theme configuration
     */
    @Transactional
    public ThemeConfigResponse createConfiguration(String clientId, Long themeId,
                                                   CreateThemeConfigurationDto dto,
                                                   String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        // Check if configuration key already exists
        if (configRepository.existsByThemeIdAndConfigKey(themeId, dto.getConfigKey())) {
            throw new InvalidThemeConfigurationException(
                    "Configuration key '" + dto.getConfigKey() + "' already exists for this theme");
        }

        ThemeConfigurations config = ThemeConfigurations.builder()
                .themeId(themeId)
                .configKey(dto.getConfigKey())
                .configValue(dto.getConfigValue())
                .configType(dto.getConfigType() != null ? dto.getConfigType() : "string")
                .build();

        ThemeConfigurations saved = configRepository.save(config);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("themeId", themeId);
        metadata.put("configKey", dto.getConfigKey());
        metadata.put("configType", saved.getConfigType());
        auditLogService.logAction(clientId, getCurrentUserId(request), "THEME_CONFIG_CREATED",
                ipAddress, userAgent, metadata);

        log.info("Created theme configuration: {} for theme: {}", dto.getConfigKey(), themeId);
        return mapToResponse(saved);
    }

    /**
     * Update a theme configuration
     */
    @Transactional
    public ThemeConfigResponse updateConfiguration(String clientId, Long themeId, Long configId,
                                                   CreateThemeConfigurationDto dto,
                                                   String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        ThemeConfigurations config = configRepository.findById(configId)
                .orElseThrow(() -> new InvalidThemeConfigurationException("Configuration not found"));

        if (!config.getThemeId().equals(themeId)) {
            throw new InvalidThemeConfigurationException("Configuration does not belong to this theme");
        }

        Map<String, Object> beforeState = new HashMap<>();
        beforeState.put("configKey", config.getConfigKey());
        beforeState.put("configValue", config.getConfigValue());
        beforeState.put("configType", config.getConfigType());

        // Update fields
        if (dto.getConfigValue() != null) {
            config.setConfigValue(dto.getConfigValue());
        }
        if (dto.getConfigType() != null) {
            config.setConfigType(dto.getConfigType());
        }

        ThemeConfigurations updated = configRepository.save(config);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("themeId", themeId);
        metadata.put("configId", configId);
        metadata.put("configKey", config.getConfigKey());
        metadata.put("before", beforeState);
        metadata.put("after", Map.of(
                "configValue", updated.getConfigValue(),
                "configType", updated.getConfigType()));
        auditLogService.logAction(clientId, getCurrentUserId(request), "THEME_CONFIG_UPDATED",
                ipAddress, userAgent, metadata);

        log.info("Updated theme configuration: {} for theme: {}", configId, themeId);
        return mapToResponse(updated);
    }

    /**
     * Get all configurations for a theme
     */
    @Transactional(readOnly = true)
    public List<ThemeConfigResponse> getConfigurationsByTheme(String clientId, Long themeId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        return configRepository.findByThemeId(themeId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific configuration
     */
    @Transactional(readOnly = true)
    public ThemeConfigResponse getConfiguration(String clientId, Long themeId, Long configId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        ThemeConfigurations config = configRepository.findById(configId)
                .orElseThrow(() -> new InvalidThemeConfigurationException("Configuration not found"));

        if (!config.getThemeId().equals(themeId)) {
            throw new InvalidThemeConfigurationException("Configuration does not belong to this theme");
        }

        return mapToResponse(config);
    }

    /**
     * Delete a theme configuration
     */
    @Transactional
    public void deleteConfiguration(String clientId, Long themeId, Long configId,
                                   String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        ThemeConfigurations config = configRepository.findById(configId)
                .orElseThrow(() -> new InvalidThemeConfigurationException("Configuration not found"));

        if (!config.getThemeId().equals(themeId)) {
            throw new InvalidThemeConfigurationException("Configuration does not belong to this theme");
        }

        String configKey = config.getConfigKey();
        configRepository.delete(config);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("themeId", themeId);
        metadata.put("configId", configId);
        metadata.put("configKey", configKey);
        auditLogService.logAction(clientId, getCurrentUserId(request), "THEME_CONFIG_DELETED",
                ipAddress, userAgent, metadata);

        log.info("Deleted theme configuration: {} for theme: {}", configId, themeId);
    }

    /**
     * Map entity to response DTO
     */
    private ThemeConfigResponse mapToResponse(ThemeConfigurations config) {
        return ThemeConfigResponse.builder()
                .id(config.getId())
                .themeId(config.getThemeId())
                .configKey(config.getConfigKey())
                .configValue(config.getConfigValue())
                .configType(config.getConfigType())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}


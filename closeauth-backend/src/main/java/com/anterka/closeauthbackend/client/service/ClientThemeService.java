package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.entity.ClientThemes;
import com.anterka.closeauthbackend.client.repository.ClientThemeRepository;
import com.anterka.closeauthbackend.client.dto.request.CreateClientThemeDto;
import com.anterka.closeauthbackend.client.dto.request.UpdateClientThemeDto;
import com.anterka.closeauthbackend.client.dto.response.ThemeResponse;
import com.anterka.closeauthbackend.common.exception.ClientOwnershipException;
import com.anterka.closeauthbackend.common.exception.ThemeActivationException;
import com.anterka.closeauthbackend.common.exception.ThemeNotFoundException;
import com.anterka.closeauthbackend.client.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.audit.service.AuditLogService;
import com.anterka.closeauthbackend.user.security.UserContextHelper;
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
public class ClientThemeService {

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
     * Create a new theme
     */
    @Transactional
    public ThemeResponse createTheme(String clientId, CreateClientThemeDto dto,
                                     String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        // Check if theme name already exists
        if (themeRepository.existsByClientIdAndThemeName(clientId, dto.getThemeName())) {
            throw new ThemeActivationException("Theme '" + dto.getThemeName() + "' already exists for this client");
        }

        ClientThemes theme = ClientThemes.builder()
                .clientId(clientId)
                .themeName(dto.getThemeName())
                .isActive(false) // New themes are inactive by default
                .isDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false)
                .logoUrl(dto.getLogoUrl())
                .lightPrimaryColor(dto.getLightPrimaryColor())
                .lightBackgroundColor(dto.getLightBackgroundColor())
                .lightButtonColor(dto.getLightButtonColor())
                .lightTextColor(dto.getLightTextColor())
                .darkPrimaryColor(dto.getDarkPrimaryColor())
                .darkBackgroundColor(dto.getDarkBackgroundColor())
                .darkButtonColor(dto.getDarkButtonColor())
                .darkTextColor(dto.getDarkTextColor())
                .defaultMode(dto.getDefaultMode() != null ? dto.getDefaultMode() : "light")
                .allowModeToggle(dto.getAllowModeToggle() != null ? dto.getAllowModeToggle() : true)
                .build();

        ClientThemes saved = themeRepository.save(theme);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("themeId", saved.getId());
        metadata.put("themeName", dto.getThemeName());
        auditLogService.logAction(clientId, getCurrentUserId(request), "THEME_CREATED",
                ipAddress, userAgent, metadata);

        log.info("Created theme: {} for client: {}", dto.getThemeName(), clientId);
        return mapToResponse(saved);
    }

    /**
     * Create default theme
     */
    @Transactional
    public ClientThemes createDefaultTheme(String clientId) {
        // Check if default theme already exists
        if (themeRepository.findByClientIdAndIsDefaultTrue(clientId).isPresent()) {
            log.info("Default theme already exists for client: {}", clientId);
            return themeRepository.findByClientIdAndIsDefaultTrue(clientId).get();
        }

        ClientThemes theme = ClientThemes.builder()
                .clientId(clientId)
                .themeName("default")
                .isActive(true)
                .isDefault(true)
                .defaultMode("light")
                .allowModeToggle(true)
                .lightPrimaryColor("#007bff")
                .lightBackgroundColor("#ffffff")
                .lightButtonColor("#007bff")
                .lightTextColor("#212529")
                .darkPrimaryColor("#0d6efd")
                .darkBackgroundColor("#1a1a1a")
                .darkButtonColor("#0d6efd")
                .darkTextColor("#f8f9fa")
                .build();

        ClientThemes saved = themeRepository.save(theme);
        log.info("Created default theme for client: {}", clientId);
        return saved;
    }

    /**
     * Update a theme
     */
    @Transactional
    public ThemeResponse updateTheme(String clientId, Long themeId, UpdateClientThemeDto dto,
                                     String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        Map<String, Object> beforeState = captureState(theme);

        // Update fields if provided
        if (dto.getLogoUrl() != null) {
            theme.setLogoUrl(dto.getLogoUrl());
        }
        if (dto.getLightPrimaryColor() != null) {
            theme.setLightPrimaryColor(dto.getLightPrimaryColor());
        }
        if (dto.getLightBackgroundColor() != null) {
            theme.setLightBackgroundColor(dto.getLightBackgroundColor());
        }
        if (dto.getLightButtonColor() != null) {
            theme.setLightButtonColor(dto.getLightButtonColor());
        }
        if (dto.getLightTextColor() != null) {
            theme.setLightTextColor(dto.getLightTextColor());
        }
        if (dto.getDarkPrimaryColor() != null) {
            theme.setDarkPrimaryColor(dto.getDarkPrimaryColor());
        }
        if (dto.getDarkBackgroundColor() != null) {
            theme.setDarkBackgroundColor(dto.getDarkBackgroundColor());
        }
        if (dto.getDarkButtonColor() != null) {
            theme.setDarkButtonColor(dto.getDarkButtonColor());
        }
        if (dto.getDarkTextColor() != null) {
            theme.setDarkTextColor(dto.getDarkTextColor());
        }
        if (dto.getDefaultMode() != null) {
            theme.setDefaultMode(dto.getDefaultMode());
        }
        if (dto.getAllowModeToggle() != null) {
            theme.setAllowModeToggle(dto.getAllowModeToggle());
        }

        // Handle activation toggle logic
        if (dto.getIsActive() != null && dto.getIsActive() && !theme.getIsActive()) {
            activateTheme(clientId, themeId, request);
        }

        ClientThemes updated = themeRepository.save(theme);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("themeId", themeId);
        metadata.put("themeName", theme.getThemeName());
        metadata.put("before", beforeState);
        metadata.put("after", captureState(updated));
        auditLogService.logAction(clientId, getCurrentUserId(request), "THEME_UPDATED",
                ipAddress, userAgent, metadata);

        log.info("Updated theme: {} for client: {}", themeId, clientId);
        return mapToResponse(updated);
    }

    /**
     * Activate a theme (deactivates all other themes for the client)
     */
    @Transactional
    public ThemeResponse activateTheme(String clientId, Long themeId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        // Deactivate all other themes for this client
        themeRepository.deactivateAllThemesForClient(clientId);

        // Activate this theme
        theme.setIsActive(true);
        ClientThemes updated = themeRepository.save(theme);

        log.info("Activated theme: {} for client: {}, deactivated all others", themeId, clientId);
        return mapToResponse(updated);
    }

    /**
     * Get all themes for a client
     */
    @Transactional(readOnly = true)
    public List<ThemeResponse> getThemesByClient(String clientId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);
        return themeRepository.findByClientId(clientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific theme
     */
    @Transactional(readOnly = true)
    public ThemeResponse getTheme(String clientId, Long themeId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        return mapToResponse(theme);
    }

    /**
     * Get the active theme for a client
     */
    @Transactional(readOnly = true)
    public ThemeResponse getActiveTheme(String clientId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findByClientIdAndIsActiveTrue(clientId)
                .orElseThrow(() -> new ThemeNotFoundException("No active theme found for client"));

        return mapToResponse(theme);
    }

    /**
     * Delete a theme
     */
    @Transactional
    public void deleteTheme(String clientId, Long themeId, String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ClientThemes theme = themeRepository.findById(themeId)
                .orElseThrow(() -> new ThemeNotFoundException("Theme not found"));

        if (!theme.getClientId().equals(clientId)) {
            throw new ClientOwnershipException("Theme does not belong to this client");
        }

        if (theme.getIsActive()) {
            throw new ThemeActivationException("Cannot delete active theme. Please activate another theme first.");
        }

        String themeName = theme.getThemeName();
        themeRepository.delete(theme);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("themeId", themeId);
        metadata.put("themeName", themeName);
        auditLogService.logAction(clientId, getCurrentUserId(request), "THEME_DELETED",
                ipAddress, userAgent, metadata);

        log.info("Deleted theme: {} for client: {}", themeId, clientId);
    }

    /**
     * Capture current state for audit logging
     */
    private Map<String, Object> captureState(ClientThemes theme) {
        Map<String, Object> state = new HashMap<>();
        state.put("themeName", theme.getThemeName());
        state.put("isActive", theme.getIsActive());
        state.put("defaultMode", theme.getDefaultMode());
        state.put("lightPrimaryColor", theme.getLightPrimaryColor());
        state.put("darkPrimaryColor", theme.getDarkPrimaryColor());
        return state;
    }

    /**
     * Map entity to response DTO
     */
    private ThemeResponse mapToResponse(ClientThemes theme) {
        return ThemeResponse.builder()
                .id(theme.getId())
                .clientId(theme.getClientId())
                .themeName(theme.getThemeName())
                .isActive(theme.getIsActive())
                .isDefault(theme.getIsDefault())
                .logoUrl(theme.getLogoUrl())
                .lightPrimaryColor(theme.getLightPrimaryColor())
                .lightBackgroundColor(theme.getLightBackgroundColor())
                .lightButtonColor(theme.getLightButtonColor())
                .lightTextColor(theme.getLightTextColor())
                .darkPrimaryColor(theme.getDarkPrimaryColor())
                .darkBackgroundColor(theme.getDarkBackgroundColor())
                .darkButtonColor(theme.getDarkButtonColor())
                .darkTextColor(theme.getDarkTextColor())
                .defaultMode(theme.getDefaultMode())
                .allowModeToggle(theme.getAllowModeToggle())
                .createdAt(theme.getCreatedAt())
                .updatedAt(theme.getUpdatedAt())
                .build();
    }
}


package com.anterka.closeauthbackend.client.controller;

import com.anterka.closeauthbackend.client.dto.response.ApplicationRoleResponse;
import com.anterka.closeauthbackend.client.dto.response.RegistrationConfigResponse;
import com.anterka.closeauthbackend.client.dto.response.ThemeConfigResponse;
import com.anterka.closeauthbackend.client.dto.response.ThemeResponse;
import com.anterka.closeauthbackend.client.dto.request.*;
import com.anterka.closeauthbackend.client.service.ApplicationRegistrationConfigService;
import com.anterka.closeauthbackend.client.service.ApplicationRoleService;
import com.anterka.closeauthbackend.client.service.ClientThemeService;
import com.anterka.closeauthbackend.client.service.ThemeConfigurationService;
import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.common.dto.ResponseStatusEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST controller for managing client configurations:
 * - Application roles
 * - Registration configuration
 * - Themes
 * - Theme configurations
 *
 * All endpoints require OAuth2 Bearer token with 'client.create' scope
 * and X-User-Token header for user authentication.
 */
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
@Slf4j
public class ClientConfigurationController {

    private final ApplicationRoleService roleService;
    private final ApplicationRegistrationConfigService registrationConfigService;
    private final ClientThemeService themeService;
    private final ThemeConfigurationService themeConfigService;

    // ========================================
    // APPLICATION ROLES ENDPOINTS
    // ========================================

    @PostMapping("/{clientId}/roles")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ApplicationRoleResponse>> createRole(
            @PathVariable String clientId,
            @Valid @RequestBody CreateApplicationRoleDto dto,
            HttpServletRequest request) {

        log.info("Creating role for client: {}", clientId);
        ApplicationRoleResponse response = roleService.createRole(
                clientId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomApiResponse.<ApplicationRoleResponse>builder()
                        .timestamp(LocalDateTime.now())
                        .status(ResponseStatusEnum.SUCCESS)
                        .message("Role created successfully")
                        .data(response)
                        .build());
    }

    @GetMapping("/{clientId}/roles")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<List<ApplicationRoleResponse>>> getRoles(
            @PathVariable String clientId,HttpServletRequest request) {

        log.info("Getting roles for client: {}", clientId);
        List<ApplicationRoleResponse> roles = roleService.getRolesByClient(clientId,request);

        return ResponseEntity.ok(CustomApiResponse.<List<ApplicationRoleResponse>>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Roles retrieved successfully")
                .data(roles)
                .build());
    }

    @GetMapping("/{clientId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ApplicationRoleResponse>> getRole(
            @PathVariable String clientId,
            @PathVariable Integer roleId,HttpServletRequest request) {

        log.info("Getting role {} for client: {}", roleId, clientId);
        ApplicationRoleResponse response = roleService.getRole(clientId, roleId,request);

        return ResponseEntity.ok(CustomApiResponse.<ApplicationRoleResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Role retrieved successfully")
                .data(response)
                .build());
    }

    @PutMapping("/{clientId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ApplicationRoleResponse>> updateRole(
            @PathVariable String clientId,
            @PathVariable Integer roleId,
            @Valid @RequestBody UpdateApplicationRoleDto dto,
            HttpServletRequest request) {

        log.info("Updating role {} for client: {}", roleId, clientId);
        ApplicationRoleResponse response = roleService.updateRole(
                clientId, roleId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.ok(CustomApiResponse.<ApplicationRoleResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Role updated successfully")
                .data(response)
                .build());
    }

    @DeleteMapping("/{clientId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> deleteRole(
            @PathVariable String clientId,
            @PathVariable Integer roleId,
            HttpServletRequest request) {

        log.info("Deleting role {} for client: {}", roleId, clientId);
        roleService.deleteRole(
                clientId, roleId,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Role deleted successfully")
                .build());
    }

    // ========================================
    // REGISTRATION CONFIG ENDPOINTS
    // ========================================

    @GetMapping("/{clientId}/registration-config")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<RegistrationConfigResponse>> getRegistrationConfig(
            @PathVariable String clientId,HttpServletRequest request) {

        log.info("Getting registration config for client: {}", clientId);
        RegistrationConfigResponse response = registrationConfigService.getConfig(clientId,request);

        return ResponseEntity.ok(CustomApiResponse.<RegistrationConfigResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Registration config retrieved successfully")
                .data(response)
                .build());
    }

    @PutMapping("/{clientId}/registration-config")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<RegistrationConfigResponse>> updateRegistrationConfig(
            @PathVariable String clientId,
            @Valid @RequestBody UpdateApplicationRegistrationConfigDto dto,
            HttpServletRequest request) {

        log.info("Updating registration config for client: {}", clientId);
        RegistrationConfigResponse response = registrationConfigService.updateConfig(
                clientId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"), request);

        return ResponseEntity.ok(CustomApiResponse.<RegistrationConfigResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Registration config updated successfully")
                .data(response)
                .build());
    }

    // ========================================
    // THEMES ENDPOINTS
    // ========================================

    @PostMapping("/{clientId}/themes")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeResponse>> createTheme(
            @PathVariable String clientId,
            @Valid @RequestBody CreateClientThemeDto dto,
            HttpServletRequest request) {

        log.info("Creating theme for client: {}", clientId);
        ThemeResponse response = themeService.createTheme(
                clientId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomApiResponse.<ThemeResponse>builder()
                        .timestamp(LocalDateTime.now())
                        .status(ResponseStatusEnum.SUCCESS)
                        .message("Theme created successfully")
                        .data(response)
                        .build());
    }

    @GetMapping("/{clientId}/themes")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<List<ThemeResponse>>> getThemes(
            @PathVariable String clientId,HttpServletRequest request) {

        log.info("Getting themes for client: {}", clientId);
        List<ThemeResponse> themes = themeService.getThemesByClient(clientId,request);

        return ResponseEntity.ok(CustomApiResponse.<List<ThemeResponse>>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Themes retrieved successfully")
                .data(themes)
                .build());
    }

    @GetMapping("/{clientId}/themes/{themeId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeResponse>> getTheme(
            @PathVariable String clientId,
            @PathVariable Long themeId,HttpServletRequest request) {

        log.info("Getting theme {} for client: {}", themeId, clientId);
        ThemeResponse response = themeService.getTheme(clientId, themeId,request);

        return ResponseEntity.ok(CustomApiResponse.<ThemeResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme retrieved successfully")
                .data(response)
                .build());
    }

    @GetMapping("/{clientId}/themes/active")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeResponse>> getActiveTheme(
            @PathVariable String clientId,HttpServletRequest request) {

        log.info("Getting active theme for client: {}", clientId);
        ThemeResponse response = themeService.getActiveTheme(clientId,request);

        return ResponseEntity.ok(CustomApiResponse.<ThemeResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Active theme retrieved successfully")
                .data(response)
                .build());
    }

    @PutMapping("/{clientId}/themes/{themeId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeResponse>> updateTheme(
            @PathVariable String clientId,
            @PathVariable Long themeId,
            @Valid @RequestBody UpdateClientThemeDto dto,
            HttpServletRequest request) {

        log.info("Updating theme {} for client: {}", themeId, clientId);
        ThemeResponse response = themeService.updateTheme(
                clientId, themeId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.ok(CustomApiResponse.<ThemeResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme updated successfully")
                .data(response)
                .build());
    }

    @PatchMapping("/{clientId}/themes/{themeId}/activate")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeResponse>> activateTheme(
            @PathVariable String clientId,
            @PathVariable Long themeId,HttpServletRequest request) {

        log.info("Activating theme {} for client: {}", themeId, clientId);
        ThemeResponse response = themeService.activateTheme(clientId, themeId,request);

        return ResponseEntity.ok(CustomApiResponse.<ThemeResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme activated successfully")
                .data(response)
                .build());
    }

    @DeleteMapping("/{clientId}/themes/{themeId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> deleteTheme(
            @PathVariable String clientId,
            @PathVariable Long themeId,
            HttpServletRequest request) {

        log.info("Deleting theme {} for client: {}", themeId, clientId);
        themeService.deleteTheme(
                clientId, themeId,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme deleted successfully")
                .build());
    }

    // ========================================
    // THEME CONFIGURATIONS ENDPOINTS
    // ========================================

    @PostMapping("/{clientId}/themes/{themeId}/configurations")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeConfigResponse>> createThemeConfiguration(
            @PathVariable String clientId,
            @PathVariable Long themeId,
            @Valid @RequestBody CreateThemeConfigurationDto dto,
            HttpServletRequest request) {

        log.info("Creating configuration for theme {} in client: {}", themeId, clientId);
        ThemeConfigResponse response = themeConfigService.createConfiguration(
                clientId, themeId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomApiResponse.<ThemeConfigResponse>builder()
                        .timestamp(LocalDateTime.now())
                        .status(ResponseStatusEnum.SUCCESS)
                        .message("Theme configuration created successfully")
                        .data(response)
                        .build());
    }

    @GetMapping("/{clientId}/themes/{themeId}/configurations")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<List<ThemeConfigResponse>>> getThemeConfigurations(
            @PathVariable String clientId,
            @PathVariable Long themeId,HttpServletRequest request) {

        log.info("Getting configurations for theme {} in client: {}", themeId, clientId);
        List<ThemeConfigResponse> configs = themeConfigService.getConfigurationsByTheme(clientId, themeId,request);

        return ResponseEntity.ok(CustomApiResponse.<List<ThemeConfigResponse>>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme configurations retrieved successfully")
                .data(configs)
                .build());
    }

    @GetMapping("/{clientId}/themes/{themeId}/configurations/{configId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeConfigResponse>> getThemeConfiguration(
            @PathVariable String clientId,
            @PathVariable Long themeId,
            @PathVariable Long configId,HttpServletRequest request) {

        log.info("Getting configuration {} for theme {} in client: {}", configId, themeId, clientId);
        ThemeConfigResponse response = themeConfigService.getConfiguration(clientId, themeId, configId,request);

        return ResponseEntity.ok(CustomApiResponse.<ThemeConfigResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme configuration retrieved successfully")
                .data(response)
                .build());
    }

    @PutMapping("/{clientId}/themes/{themeId}/configurations/{configId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<ThemeConfigResponse>> updateThemeConfiguration(
            @PathVariable String clientId,
            @PathVariable Long themeId,
            @PathVariable Long configId,
            @Valid @RequestBody CreateThemeConfigurationDto dto,
            HttpServletRequest request) {

        log.info("Updating configuration {} for theme {} in client: {}", configId, themeId, clientId);
        ThemeConfigResponse response = themeConfigService.updateConfiguration(
                clientId, themeId, configId, dto,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),request);

        return ResponseEntity.ok(CustomApiResponse.<ThemeConfigResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme configuration updated successfully")
                .data(response)
                .build());
    }

    @DeleteMapping("/{clientId}/themes/{themeId}/configurations/{configId}")
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> deleteThemeConfiguration(
            @PathVariable String clientId,
            @PathVariable Long themeId,
            @PathVariable Long configId,
            HttpServletRequest request) {

        log.info("Deleting configuration {} for theme {} in client: {}", configId, themeId, clientId);
        themeConfigService.deleteConfiguration(
                clientId, themeId, configId,
                request.getRemoteAddr(),
                request.getHeader("User-Agent"),
                request);

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .timestamp(LocalDateTime.now())
                .status(ResponseStatusEnum.SUCCESS)
                .message("Theme configuration deleted successfully")
                .build());
    }
}


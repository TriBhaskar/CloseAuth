package com.anterka.closeauthbackend.service;

import com.anterka.closeauthbackend.core.entities.Client;
import com.anterka.closeauthbackend.core.repository.ClientRepository;
import com.anterka.closeauthbackend.dto.request.CreateApplicationRoleDto;
import com.anterka.closeauthbackend.dto.request.UpdateApplicationRoleDto;
import com.anterka.closeauthbackend.dto.response.ApplicationRoleResponse;
import com.anterka.closeauthbackend.entities.ApplicationRole;
import com.anterka.closeauthbackend.entities.UserApplicationRole;
import com.anterka.closeauthbackend.entities.UserClientMap;
import com.anterka.closeauthbackend.entities.Users;
import com.anterka.closeauthbackend.exception.ClientOwnershipException;
import com.anterka.closeauthbackend.exception.RoleAlreadyExistsException;
import com.anterka.closeauthbackend.repository.ApplicationRoleRepository;
import com.anterka.closeauthbackend.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.repository.UserApplicationRoleRepository;
import com.anterka.closeauthbackend.repository.UserClientMapRepository;
import com.anterka.closeauthbackend.security.UserContextHelper;
import jakarta.persistence.EntityNotFoundException;
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
public class ApplicationRoleService {

    private final ApplicationRoleRepository applicationRoleRepository;
    private final ClientOwnershipRepository clientOwnershipRepository;
    private final ClientRepository clientRepository;
    private final UserClientMapRepository userClientMapRepository;
    private final UserApplicationRoleRepository userApplicationRoleRepository;
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
     * Create a new application role
     */
    @Transactional
    public ApplicationRoleResponse createRole(String clientId, CreateApplicationRoleDto dto,
                                             String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        // Check if role already exists
        if (applicationRoleRepository.findByClientIdAndRoleName(clientId, dto.getRoleName()).isPresent()) {
            throw new RoleAlreadyExistsException("Role '" + dto.getRoleName() + "' already exists for this client");
        }

        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        ApplicationRole role = ApplicationRole.builder()
                .client(client)
                .roleName(dto.getRoleName())
                .description(dto.getDescription())
                .permissions(dto.getPermissions())
                .isDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false)
                .build();

        ApplicationRole saved = applicationRoleRepository.save(role);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("roleName", dto.getRoleName());
        metadata.put("isDefault", saved.getIsDefault());
        auditLogService.logAction(clientId, getCurrentUserId(request), "ROLE_CREATED",
                ipAddress, userAgent, metadata);

        log.info("Created application role: {} for client: {}", dto.getRoleName(), clientId);
        return mapToResponse(saved);
    }

    /**
     * Create default role and auto-assign to client owner
     */
    @Transactional
    public ApplicationRole createDefaultRole(String clientId, Integer ownerId) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        // Check if default role already exists
        if (applicationRoleRepository.findByClientIdAndRoleName(clientId, "USER").isPresent()) {
            log.info("Default USER role already exists for client: {}", clientId);
            return applicationRoleRepository.findByClientIdAndRoleName(clientId, "USER").get();
        }

        ApplicationRole role = ApplicationRole.builder()
                .client(client)
                .roleName("USER")
                .description("Default user role with basic permissions")
                .permissions("{\"read\":[\"profile\"],\"write\":[\"own_profile\"]}")
                .isDefault(true)
                .build();

        ApplicationRole saved = applicationRoleRepository.save(role);
        log.info("Created default USER role for client: {}", clientId);

        // Auto-assign to owner
        autoAssignRoleToOwner(clientId, ownerId, saved);

        return saved;
    }

    /**
     * Auto-assign role to client owner
     */
    @Transactional
    public void autoAssignRoleToOwner(String clientId, Integer userId, ApplicationRole role) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Client not found"));

        // Get the user
        Users user = clientRepository.findById(clientId)
                .flatMap(c -> clientOwnershipRepository.findByClient_Id(clientId))
                .map(ownership -> ownership.getUser())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        // Find or create user-client mapping
        UserClientMap userClientMap = userClientMapRepository
                .findByUser_IdAndClient_Id(userId, clientId)
                .orElseGet(() -> {
                    log.info("Creating user-client mapping for owner userId: {} and clientId: {}", userId, clientId);

                    UserClientMap newMap = UserClientMap.builder()
                            .user(user)
                            .client(client)
                            .status("APPROVED")
                            .build();
                    return userClientMapRepository.save(newMap);
                });

        // Check if role already assigned
        if (userApplicationRoleRepository.findByUserClientMapAndApplicationRole(userClientMap, role).isPresent()) {
            log.info("Role already assigned to user");
            return;
        }

        // Assign role
        UserApplicationRole userRole = UserApplicationRole.builder()
                .userClientMap(userClientMap)
                .applicationRole(role)
                .assignedBy(user)  // Use Users object, not Integer
                .build();

        userApplicationRoleRepository.save(userRole);
        log.info("Auto-assigned role {} to client owner", role.getRoleName());
    }

    /**
     * Update an existing role
     */
    @Transactional
    public ApplicationRoleResponse updateRole(String clientId, Integer roleId,
                                             UpdateApplicationRoleDto dto,
                                             String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ApplicationRole role = applicationRoleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Role not found"));

        if (!role.getClient().getId().equals(clientId)) {
            throw new ClientOwnershipException("Role does not belong to this client");
        }

        Map<String, Object> beforeState = new HashMap<>();
        beforeState.put("description", role.getDescription());
        beforeState.put("permissions", role.getPermissions());
        beforeState.put("isDefault", role.getIsDefault());

        if (dto.getDescription() != null) {
            role.setDescription(dto.getDescription());
        }
        if (dto.getPermissions() != null) {
            role.setPermissions(dto.getPermissions());
        }
        if (dto.getIsDefault() != null) {
            role.setIsDefault(dto.getIsDefault());
        }

        ApplicationRole updated = applicationRoleRepository.save(role);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("roleId", roleId);
        metadata.put("roleName", role.getRoleName());
        metadata.put("before", beforeState);
        metadata.put("after", Map.of(
                "description", updated.getDescription(),
                "permissions", updated.getPermissions(),
                "isDefault", updated.getIsDefault()));
        auditLogService.logAction(clientId, getCurrentUserId(request), "ROLE_UPDATED",
                ipAddress, userAgent, metadata);

        log.info("Updated role: {} for client: {}", roleId, clientId);
        return mapToResponse(updated);
    }

    /**
     * Get all roles for a client
     */
    @Transactional(readOnly = true)
    public List<ApplicationRoleResponse> getRolesByClient(String clientId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);
        return applicationRoleRepository.findByClientId(clientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific role
     */
    @Transactional(readOnly = true)
    public ApplicationRoleResponse getRole(String clientId, Integer roleId, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ApplicationRole role = applicationRoleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Role not found"));

        if (!role.getClient().getId().equals(clientId)) {
            throw new ClientOwnershipException("Role does not belong to this client");
        }

        return mapToResponse(role);
    }

    /**
     * Delete a role
     */
    @Transactional
    public void deleteRole(String clientId, Integer roleId, String ipAddress, String userAgent, HttpServletRequest request) {
        verifyClientOwnership(clientId, request);

        ApplicationRole role = applicationRoleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Role not found"));

        if (!role.getClient().getId().equals(clientId)) {
            throw new ClientOwnershipException("Role does not belong to this client");
        }

        String roleName = role.getRoleName();
        applicationRoleRepository.delete(role);

        // Audit log
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("roleId", roleId);
        metadata.put("roleName", roleName);
        auditLogService.logAction(clientId, getCurrentUserId(request), "ROLE_DELETED",
                ipAddress, userAgent, metadata);

        log.info("Deleted role: {} for client: {}", roleId, clientId);
    }

    /**
     * Map entity to response DTO
     */
    private ApplicationRoleResponse mapToResponse(ApplicationRole role) {
        return ApplicationRoleResponse.builder()
                .id(role.getId())
                .clientId(role.getClient().getId())
                .roleName(role.getRoleName())
                .description(role.getDescription())
                .permissions(role.getPermissions())
                .isDefault(role.getIsDefault())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();
    }
}


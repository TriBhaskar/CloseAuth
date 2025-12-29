package com.anterka.closeauthbackend.auth.controller;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.dto.response.PendingRegistrationResponse;
import com.anterka.closeauthbackend.auth.service.RegistrationCompletionService;
import com.anterka.closeauthbackend.cache.service.AdminPendingRegistrationCacheService;
import com.anterka.closeauthbackend.client.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.common.dto.CustomApiResponse;
import com.anterka.closeauthbackend.common.dto.ResponseStatusEnum;
import com.anterka.closeauthbackend.common.exception.ClientOwnershipException;
import com.anterka.closeauthbackend.user.security.UserContextHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller for managing admin approval of pending registrations.
 * Client admins can view, approve, or reject pending user registrations.
 */
@RestController
@RequestMapping("/api/v1/admin/clients/{clientId}/pending-registrations")
@RequiredArgsConstructor
@Slf4j
public class AdminApprovalController {

    private final AdminPendingRegistrationCacheService pendingRegistrationCacheService;
    private final RegistrationCompletionService registrationCompletionService;
    private final ClientOwnershipRepository clientOwnershipRepository;

    /**
     * Get all pending registrations for a client.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<List<PendingRegistrationResponse>>> getPendingRegistrations(
            @PathVariable String clientId,
            HttpServletRequest request) {

        verifyClientOwnership(clientId, request);

        List<RegistrationData> pendingRegistrations = pendingRegistrationCacheService.getPendingApprovalsForClient(clientId);

        List<PendingRegistrationResponse> responses = pendingRegistrations.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        log.info("Retrieved {} pending registrations for client {}", responses.size(), clientId);

        return ResponseEntity.ok(CustomApiResponse.<List<PendingRegistrationResponse>>builder()
                .message("Pending registrations retrieved successfully")
                .status(ResponseStatusEnum.SUCCESS)
                .data(responses)
                .timestamp(LocalDateTime.now())
                .build());
    }

    /**
     * Get count of pending registrations for a client.
     */
    @GetMapping(value = "/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Long>> getPendingRegistrationsCount(
            @PathVariable String clientId,
            HttpServletRequest request) {

        verifyClientOwnership(clientId, request);

        long count = pendingRegistrationCacheService.countPendingApprovals(clientId);

        return ResponseEntity.ok(CustomApiResponse.<Long>builder()
                .message("Pending registrations count retrieved successfully")
                .status(ResponseStatusEnum.SUCCESS)
                .data(count)
                .timestamp(LocalDateTime.now())
                .build());
    }

    /**
     * Approve a pending registration.
     */
    @PostMapping(value = "/{email}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> approveRegistration(
            @PathVariable String clientId,
            @PathVariable String email,
            HttpServletRequest request) {

        verifyClientOwnership(clientId, request);

        log.info("Approving registration for email {} on client {}", email, clientId);

        registrationCompletionService.persistAdminApprovedUser(clientId, email);

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .message("Registration approved successfully. User account is now active.")
                .status(ResponseStatusEnum.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build());
    }

    /**
     * Reject a pending registration.
     */
    @PostMapping(value = "/{email}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_client.create')")
    public ResponseEntity<CustomApiResponse<Void>> rejectRegistration(
            @PathVariable String clientId,
            @PathVariable String email,
            HttpServletRequest request) {

        verifyClientOwnership(clientId, request);

        log.info("Rejecting registration for email {} on client {}", email, clientId);

        registrationCompletionService.rejectAdminPendingRegistration(clientId, email);

        return ResponseEntity.ok(CustomApiResponse.<Void>builder()
                .message("Registration rejected successfully.")
                .status(ResponseStatusEnum.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build());
    }

    /**
     * Verify that the current user owns the client.
     */
    private void verifyClientOwnership(String clientId, HttpServletRequest request) {
        Integer userId = UserContextHelper.getUserId(request);
        if (userId == null || !clientOwnershipRepository.existsByClient_IdAndUser_Id(clientId, userId)) {
            log.warn("User {} attempted to access pending registrations for client {} without ownership", userId, clientId);
            throw new ClientOwnershipException("You do not have permission to manage this client's registrations");
        }
    }

    /**
     * Map RegistrationData to response DTO.
     */
    private PendingRegistrationResponse mapToResponse(RegistrationData data) {
        return PendingRegistrationResponse.builder()
                .email(data.registrationDto().email())
                .username(data.registrationDto().username())
                .firstName(data.registrationDto().firstName())
                .lastName(data.registrationDto().lastName())
                .phone(data.registrationDto().phone())
                .verificationMode(data.verificationMode().name())
                .build();
    }
}


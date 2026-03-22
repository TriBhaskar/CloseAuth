package com.anterka.closeauthbackend.cache.service;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.cache.repository.AdminPendingRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing admin pending registration approvals in Redis cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPendingRegistrationCacheService {

    private final AdminPendingRegistrationRepository repository;

    /**
     * TTL for pending approvals: 7 days
     */
    private static final long PENDING_APPROVAL_TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);

    /**
     * Saves a registration for admin approval.
     */
    public void savePendingApproval(String clientId, String email, RegistrationData data) {
        repository.savePendingApproval(clientId, email, data, PENDING_APPROVAL_TTL_SECONDS);
        log.info("Saved pending approval for user {} on client {}", email, clientId);
    }

    /**
     * Gets a pending approval by client and email.
     */
    public Optional<RegistrationData> getPendingApproval(String clientId, String email) {
        return repository.getPendingApproval(clientId, email);
    }

    /**
     * Gets all pending approvals for a client.
     */
    public List<RegistrationData> getPendingApprovalsForClient(String clientId) {
        return repository.getPendingApprovalsForClient(clientId);
    }

    /**
     * Deletes a pending approval (after approval or rejection).
     */
    public void deletePendingApproval(String clientId, String email) {
        repository.deletePendingApproval(clientId, email);
        log.info("Deleted pending approval for user {} on client {}", email, clientId);
    }

    /**
     * Checks if a pending approval exists.
     */
    public boolean pendingApprovalExists(String clientId, String email) {
        return repository.pendingApprovalExists(clientId, email);
    }

    /**
     * Gets count of pending approvals for a client.
     */
    public long countPendingApprovals(String clientId) {
        return repository.countPendingApprovals(clientId);
    }
}

package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.RegistrationData;
import com.anterka.closeauthbackend.auth.dto.request.UserRegistrationDto;
import com.anterka.closeauthbackend.auth.enums.VerificationType;
import com.anterka.closeauthbackend.cache.service.AdminPendingRegistrationCacheService;
import com.anterka.closeauthbackend.cache.service.RegistrationCacheService;
import com.anterka.closeauthbackend.client.entity.Client;
import com.anterka.closeauthbackend.client.entity.UserClientMap;
import com.anterka.closeauthbackend.client.repository.ClientRepository;
import com.anterka.closeauthbackend.client.repository.UserClientMapRepository;
import com.anterka.closeauthbackend.common.exception.UserRegistrationException;
import com.anterka.closeauthbackend.user.entity.Users;
import com.anterka.closeauthbackend.user.enums.GlobalRoleEnum;
import com.anterka.closeauthbackend.user.repository.GlobalRolesRepository;
import com.anterka.closeauthbackend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * Service responsible for completing user registration after verification.
 * Handles persisting users to database and creating UserClientMap entries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationCompletionService {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final UserClientMapRepository userClientMapRepository;
    private final GlobalRolesRepository globalRolesRepository;
    private final RegistrationCacheService registrationCacheService;
    private final AdminPendingRegistrationCacheService adminPendingCacheService;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;

    private static final String USER_CLIENT_STATUS_ACTIVE = "ACTIVE";
    private static final String USER_STATUS_ACTIVE = "ACTIVE";

    /**
     * Completes a verification step and persists user if all verifications are done.
     *
     * @param email The user's email (used as key for registration data)
     * @param verificationType The type of verification that was completed
     * @return true if registration is now complete (user persisted), false if more verifications needed
     */
    @Transactional
    public boolean completeVerification(String email, VerificationType verificationType) {
        RegistrationData registrationData = registrationCacheService.getRegistration(email)
                .orElseThrow(() -> new UserRegistrationException("No pending registration found for email: " + email));

        // Remove the completed verification from pending set
        Set<VerificationType> remaining = new HashSet<>(registrationData.pendingVerifications());
        remaining.remove(verificationType);

        log.info("Verification {} completed for {}. Remaining verifications: {}", verificationType, email, remaining);

        if (remaining.isEmpty()) {
            // All verifications complete - persist user
            persistUser(registrationData);

            // Clean up cache
            registrationCacheService.deleteRegistration(email);
            otpService.deleteOtp(email);

            String phone = registrationData.registrationDto().phone();
            if (phone != null && !phone.isBlank()) {
                otpService.deletePhoneOtp(phone);
            }

            log.info("Registration completed and user persisted for: {}", email);
            return true;
        } else {
            // Update registration data with remaining verifications
            RegistrationData updatedData = new RegistrationData(
                    registrationData.registrationDto(),
                    registrationData.globalRoleEnum(),
                    registrationData.clientId(),
                    registrationData.verificationMode(),
                    remaining
            );
            registrationCacheService.saveRegistration(email, updatedData);

            log.info("Registration updated for {}. Still pending: {}", email, remaining);
            return false;
        }
    }

    /**
     * Persists a user to the database with ACTIVE status.
     * Also creates the UserClientMap entry.
     */
    @Transactional
    public Users persistUser(RegistrationData registrationData) {
        UserRegistrationDto dto = registrationData.registrationDto();
        String clientId = registrationData.clientId();

        // Create user entity
        Users user = Users.builder()
                .username(dto.username())
                .email(dto.email())
                .passwordHash(passwordEncoder.encode(dto.password()))
                .algo("bcrypt")
                .firstName(dto.firstName())
                .lastName(dto.lastName())
                .phone(dto.phone())
                .status(USER_STATUS_ACTIVE)
                .emailVerified(true)
                .phoneVerified(dto.phone() != null && !dto.phone().isBlank())
                .globalRoles(globalRolesRepository.findByRole(GlobalRoleEnum.END_USER)
                        .orElseThrow(() -> new UserRegistrationException("Default role not found")))
                .build();

        user = userRepository.save(user);
        log.info("User persisted: id={}, email={}", user.getId(), user.getEmail());

        // Create UserClientMap entry if clientId is provided
        if (clientId != null && !clientId.isBlank()) {
            createUserClientMapping(user, clientId);
        }

        return user;
    }

    /**
     * Persists a user for admin-approved registration.
     * Called when admin approves a pending registration.
     */
    @Transactional
    public Users persistAdminApprovedUser(String clientId, String email) {
        RegistrationData registrationData = adminPendingCacheService.getPendingApproval(clientId, email)
                .orElseThrow(() -> new UserRegistrationException("No pending approval found for email: " + email));

        Users user = persistUser(registrationData);

        // Clean up admin pending cache
        adminPendingCacheService.deletePendingApproval(clientId, email);

        log.info("Admin-approved user persisted: email={}, clientId={}", email, clientId);
        return user;
    }

    /**
     * Rejects an admin-pending registration.
     * Simply removes the registration from cache.
     */
    public void rejectAdminPendingRegistration(String clientId, String email) {
        if (!adminPendingCacheService.pendingApprovalExists(clientId, email)) {
            throw new UserRegistrationException("No pending approval found for email: " + email);
        }

        adminPendingCacheService.deletePendingApproval(clientId, email);
        log.info("Admin-pending registration rejected: email={}, clientId={}", email, clientId);
    }

    /**
     * Creates the UserClientMap entry linking user to client.
     */
    private void createUserClientMapping(Users user, String clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new UserRegistrationException("Client not found: " + clientId));

        UserClientMap mapping = UserClientMap.builder()
                .user(user)
                .client(client)
                .status(USER_CLIENT_STATUS_ACTIVE)
                .build();

        userClientMapRepository.save(mapping);
        log.info("UserClientMap created: userId={}, clientId={}, status={}",
                user.getId(), clientId, USER_CLIENT_STATUS_ACTIVE);
    }

    /**
     * Persists user immediately for AUTO_APPROVE mode.
     * Called directly after registration without any verification.
     */
    @Transactional
    public Users persistUserImmediately(RegistrationData registrationData) {
        Users user = persistUser(registrationData);

        // Clean up registration cache
        registrationCacheService.deleteRegistration(registrationData.registrationDto().email());

        log.info("User auto-approved and persisted: {}", registrationData.registrationDto().email());
        return user;
    }
}


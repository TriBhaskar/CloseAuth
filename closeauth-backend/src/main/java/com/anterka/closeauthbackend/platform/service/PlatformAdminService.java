package com.anterka.closeauthbackend.platform.service;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.PasswordHasher;
import com.anterka.closeauthbackend.common.security.PasswordHasher.HashedPassword;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.platform.dto.CreatePlatformAdminCommand;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminAuthResult;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminAuthResult.FailureReason;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.entity.PlatformAdmin;
import com.anterka.closeauthbackend.platform.entity.PlatformAdminRole;
import com.anterka.closeauthbackend.platform.enums.PlatformAdminStatus;
import com.anterka.closeauthbackend.platform.repository.PlatformAdminRepository;
import com.anterka.closeauthbackend.platform.repository.PlatformAdminRoleRepository;
import com.anterka.closeauthbackend.rbac.entity.PlatformRole;
import com.anterka.closeauthbackend.rbac.repository.PlatformRoleRepository;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-admin domain service (§7.8) — CloseAuth staff who operate across all tenants. <b>NOT tenant-scoped</b>:
 * these operations take no {@code TenantContext} and operate platform-wide (the correct bare-operation case, per the
 * {@code TenantContext} "acted-on vs operated-within" rule). Owns platform-admin lifecycle, platform-role assignment,
 * and an enumeration-safe {@code authenticate} (the platform analogue of 3b's {@code verifyPassword}). Reuses the
 * shared {@link PasswordHasher} (no duplicated hashing).
 */
@Service
@Slf4j
public class PlatformAdminService {

    /** Non-secret constant, used only to burn comparable time on the "no admin" path (timing equalization). */
    private static final String TIMING_GUARD_RAW = "timing-guard-not-a-real-password";

    private final PlatformAdminRepository platformAdminRepository;
    private final PlatformAdminRoleRepository platformAdminRoleRepository;
    private final PlatformRoleRepository platformRoleRepository;
    private final PasswordHasher passwordHasher;
    private final CommandValidator commandValidator;
    private final TokenRevocationService tokenRevocationService;
    private final String timingGuardHash;

    public PlatformAdminService(PlatformAdminRepository platformAdminRepository,
                                PlatformAdminRoleRepository platformAdminRoleRepository,
                                PlatformRoleRepository platformRoleRepository,
                                PasswordHasher passwordHasher,
                                CommandValidator commandValidator,
                                TokenRevocationService tokenRevocationService) {
        this.platformAdminRepository = platformAdminRepository;
        this.platformAdminRoleRepository = platformAdminRoleRepository;
        this.platformRoleRepository = platformRoleRepository;
        this.passwordHasher = passwordHasher;
        this.commandValidator = commandValidator;
        this.tokenRevocationService = tokenRevocationService;
        this.timingGuardHash = passwordHasher.hash(TIMING_GUARD_RAW).hash();
    }

    // ---- lifecycle ---------------------------------------------------------

    @Transactional
    public PlatformAdminView createPlatformAdmin(CreatePlatformAdminCommand command) {
        commandValidator.validate(command);
        String email = normalize(command.email());
        if (platformAdminRepository.existsByEmail(email)) {
            throw new CloseAuthDomainException(ErrorCategory.CONFLICT, "platform_admin.email_exists",
                    "A platform admin with this email already exists");
        }
        HashedPassword hashed = passwordHasher.hash(command.password());
        PlatformAdmin admin = new PlatformAdmin();
        admin.setEmail(email);
        admin.setStatus(PlatformAdminStatus.ACTIVE);
        admin.setFirstName(command.firstName());
        admin.setLastName(command.lastName());
        admin.setPasswordHash(hashed.hash());
        admin.setPasswordAlgo(hashed.algorithm());
        return PlatformAdminView.from(platformAdminRepository.save(admin));
    }

    @Transactional(readOnly = true)
    public PlatformAdminView getById(UUID id) {
        return PlatformAdminView.from(loadOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<PlatformAdminView> list() {
        return platformAdminRepository.findAll().stream().map(PlatformAdminView::from).toList();
    }

    /**
     * {@code ACTIVE → SUSPENDED}. A suspended platform admin cannot authenticate for a NEW token — and, crucially, any
     * token ALREADY minted for them is revoked instantly: we write the sub-keyed platform-admin revocation marker so a
     * compromised or in-flight token is killed within the token TTL (introspection reports {@code active:false} and the
     * {@code /v1/**} chain rejects it), not merely at expiry. This closes the §7.8 revocability condition — the
     * highest-privilege credential must be killable, not just short-lived.
     */
    @Transactional
    public PlatformAdminView suspend(UUID id) {
        PlatformAdmin admin = loadOrThrow(id);
        admin.setStatus(PlatformAdminStatus.SUSPENDED);
        tokenRevocationService.revokePlatformAdminTokens(id);
        return PlatformAdminView.from(admin);
    }

    /** {@code SUSPENDED → ACTIVE}. */
    @Transactional
    public PlatformAdminView activate(UUID id) {
        PlatformAdmin admin = loadOrThrow(id);
        admin.setStatus(PlatformAdminStatus.ACTIVE);
        return PlatformAdminView.from(admin);
    }

    // ---- platform-role assignment -----------------------------------------

    @Transactional
    public void assignRole(UUID adminId, String roleName) {
        loadOrThrow(adminId);
        PlatformRole role = platformRoleRepository.findByName(roleName)
                .orElseThrow(() -> new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "platform_role.not_found",
                        "Unknown platform role: " + roleName));
        if (!platformAdminRoleRepository.existsByPlatformAdminIdAndPlatformRoleId(adminId, role.getId())) {
            PlatformAdminRole assignment = new PlatformAdminRole();
            assignment.setPlatformAdminId(adminId);
            assignment.setPlatformRoleId(role.getId());
            platformAdminRoleRepository.save(assignment);
        }
    }

    @Transactional
    public void revokeRole(UUID adminId, String roleName) {
        PlatformRole role = platformRoleRepository.findByName(roleName)
                .orElseThrow(() -> new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "platform_role.not_found",
                        "Unknown platform role: " + roleName));
        platformAdminRoleRepository.findByPlatformAdminIdAndPlatformRoleId(adminId, role.getId())
                .ifPresent(platformAdminRoleRepository::delete);
    }

    /** The platform-role NAMES held by an admin (sorted) — sourced into the platform-admin token's {@code roles} claim. */
    @Transactional(readOnly = true)
    public List<String> resolveRoleNames(UUID adminId) {
        List<UUID> roleIds = platformAdminRoleRepository.findByPlatformAdminId(adminId).stream()
                .map(PlatformAdminRole::getPlatformRoleId)
                .toList();
        return platformRoleRepository.findAllById(roleIds).stream()
                .map(PlatformRole::getName)
                .sorted()
                .toList();
    }

    // ---- authentication (enumeration-safe) --------------------------------

    /**
     * Verifies a platform admin's credentials — enumeration-safe (uniform failure; a timing-guard hash runs on the
     * no-admin path). Only {@code ACTIVE} admins authenticate. On success, records {@code last_login_at}.
     */
    @Transactional
    public PlatformAdminAuthResult authenticate(String email, String rawPassword) {
        Optional<PlatformAdmin> maybe = platformAdminRepository.findByEmail(normalize(email));
        if (maybe.isEmpty()) {
            passwordHasher.matches(rawPassword, timingGuardHash); // equalize timing
            return audit(FailureReason.NOT_FOUND);
        }
        PlatformAdmin admin = maybe.get();
        if (admin.getStatus() != PlatformAdminStatus.ACTIVE) {
            passwordHasher.matches(rawPassword, timingGuardHash);
            return audit(FailureReason.NOT_ACTIVE);
        }
        if (!passwordHasher.matches(rawPassword, admin.getPasswordHash())) {
            return audit(FailureReason.BAD_PASSWORD);
        }
        admin.setLastLoginAt(Instant.now());
        // TODO(stage-8): emit a PLATFORM_ADMIN_LOGIN audit event via the audit outbox (§7.11).
        return PlatformAdminAuthResult.success(admin.getId());
    }

    private PlatformAdmin loadOrThrow(UUID id) {
        return platformAdminRepository.findById(id)
                .orElseThrow(() -> new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "platform_admin.not_found",
                        "Platform admin not found"));
    }

    private PlatformAdminAuthResult audit(FailureReason reason) {
        // Server-side audit signal only; the caller surfaces a uniform, enumeration-safe failure.
        // TODO(stage-8): emit a PLATFORM_ADMIN_LOGIN_FAILED audit event (reason={}) via the audit outbox (§7.11).
        log.info("Platform-admin authentication failed ({})", reason);
        return PlatformAdminAuthResult.failure(reason);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

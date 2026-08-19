package com.anterka.closeauthbackend.platform.service;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.exception.LastPlatformAdminException;
import com.anterka.closeauthbackend.common.exception.SelfActionRefusedException;
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
import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
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

    /** The role {@code @RequiresPlatformAdmin} checks — the one the last-admin invariant protects. */
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final PlatformAdminRepository platformAdminRepository;
    private final PlatformAdminRoleRepository platformAdminRoleRepository;
    private final PlatformRoleRepository platformRoleRepository;
    private final PasswordHasher passwordHasher;
    private final CommandValidator commandValidator;
    private final TokenRevocationService tokenRevocationService;
    private final AuditEmitter auditEmitter;
    private final String timingGuardHash;

    public PlatformAdminService(PlatformAdminRepository platformAdminRepository,
                                PlatformAdminRoleRepository platformAdminRoleRepository,
                                PlatformRoleRepository platformRoleRepository,
                                PasswordHasher passwordHasher,
                                CommandValidator commandValidator,
                                TokenRevocationService tokenRevocationService,
                                AuditEmitter auditEmitter) {
        this.platformAdminRepository = platformAdminRepository;
        this.platformAdminRoleRepository = platformAdminRoleRepository;
        this.platformRoleRepository = platformRoleRepository;
        this.passwordHasher = passwordHasher;
        this.commandValidator = commandValidator;
        this.tokenRevocationService = tokenRevocationService;
        this.auditEmitter = auditEmitter;
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
        PlatformAdmin saved = platformAdminRepository.save(admin);
        auditEmitter.emit(AuditEvents.platformConfigurationChanged("PLATFORM_ADMIN_CREATED", saved.getId()));
        return PlatformAdminView.from(saved);
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
     *
     * @param actingAdminId the caller's own id (FE-3c self-lockout guard) — suspending yourself is refused
     *                      unconditionally, regardless of how many other active admins remain, since it always kills
     *                      your own live session immediately.
     */
    @Transactional
    public PlatformAdminView suspend(UUID id, UUID actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw new SelfActionRefusedException(id);
        }
        PlatformAdmin admin = loadOrThrow(id);
        // Last-platform-admin invariant (IT-9 fix): refuse to suspend the sole active PLATFORM_ADMIN, mirroring the
        // tenant tier's last-TENANT_ADMIN guard (active-only counting). Losing the last active holder would lock the
        // whole @RequiresPlatformAdmin surface out.
        if (isLastActivePlatformAdmin(id)) {
            throw new LastPlatformAdminException(id);
        }
        admin.setStatus(PlatformAdminStatus.SUSPENDED);
        tokenRevocationService.revokePlatformAdminTokens(id); // also emits TOKEN_REVOKED (scope=PLATFORM_ADMIN)
        auditEmitter.emit(AuditEvents.platformConfigurationChanged("PLATFORM_ADMIN_SUSPENDED", id));
        return PlatformAdminView.from(admin);
    }

    /** {@code SUSPENDED → ACTIVE}. */
    @Transactional
    public PlatformAdminView activate(UUID id) {
        PlatformAdmin admin = loadOrThrow(id);
        admin.setStatus(PlatformAdminStatus.ACTIVE);
        auditEmitter.emit(AuditEvents.platformConfigurationChanged("PLATFORM_ADMIN_ACTIVATED", id));
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

    /**
     * @param actingAdminId the caller's own id (FE-3c self-lockout guard) — revoking your OWN {@code PLATFORM_ADMIN}
     *                      is refused regardless of other active holders; revoking your own lesser
     *                      {@code PLATFORM_SUPPORT} is unaffected (nothing gates on it).
     */
    @Transactional
    public void revokeRole(UUID adminId, String roleName, UUID actingAdminId) {
        PlatformRole role = platformRoleRepository.findByName(roleName)
                .orElseThrow(() -> new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "platform_role.not_found",
                        "Unknown platform role: " + roleName));
        if (PLATFORM_ADMIN_ROLE.equals(role.getName()) && adminId.equals(actingAdminId)) {
            throw new SelfActionRefusedException(adminId);
        }
        // Last-platform-admin invariant (IT-9 fix): don't let a direct role-revoke strip the sole active PLATFORM_ADMIN.
        // Only PLATFORM_ADMIN is protected (PLATFORM_SUPPORT is a lesser role); a SUSPENDED holder doesn't count.
        if (PLATFORM_ADMIN_ROLE.equals(role.getName()) && isLastActivePlatformAdmin(adminId)) {
            throw new LastPlatformAdminException(adminId);
        }
        platformAdminRoleRepository.findByPlatformAdminIdAndPlatformRoleId(adminId, role.getId())
                .ifPresent(platformAdminRoleRepository::delete);
    }

    /**
     * Whether {@code targetAdminId} is currently the LAST active holder of {@code PLATFORM_ADMIN} (active-only counting,
     * matching the corrected tenant-tier discipline): they hold it and are ACTIVE, and no other active admin does.
     */
    private boolean isLastActivePlatformAdmin(UUID targetAdminId) {
        return platformRoleRepository.findByName(PLATFORM_ADMIN_ROLE).map(role -> {
            boolean targetIsActiveHolder = platformAdminRoleRepository.isActiveHolder(targetAdminId, role.getId());
            long activeHolders = platformAdminRoleRepository.countActiveHoldersByRole(role.getId());
            return targetIsActiveHolder && activeHolders <= 1;
        }).orElse(false);
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
        auditEmitter.emit(AuditEvents.platformAdminLoginSuccess(admin.getId()));
        return PlatformAdminAuthResult.success(admin.getId());
    }

    private PlatformAdmin loadOrThrow(UUID id) {
        return platformAdminRepository.findById(id)
                .orElseThrow(() -> new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "platform_admin.not_found",
                        "Platform admin not found"));
    }

    private PlatformAdminAuthResult audit(FailureReason reason) {
        // Server-side audit signal only; the caller surfaces a uniform, enumeration-safe failure.
        log.info("Platform-admin authentication failed ({})", reason);
        auditEmitter.emit(AuditEvents.platformAdminLoginFailure(reason.name()));
        return PlatformAdminAuthResult.failure(reason);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

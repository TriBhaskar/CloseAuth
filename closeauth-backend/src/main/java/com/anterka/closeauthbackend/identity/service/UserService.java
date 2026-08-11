package com.anterka.closeauthbackend.identity.service;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.EmailAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.exception.InvalidCredentialsException;
import com.anterka.closeauthbackend.common.exception.LastTenantAdminException;
import com.anterka.closeauthbackend.common.exception.LocalPasswordAlreadySetException;
import com.anterka.closeauthbackend.common.exception.UserNotFoundException;
import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.security.PasswordHasher;
import com.anterka.closeauthbackend.common.security.PasswordHasher.HashedPassword;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.identity.dto.ChangePasswordCommand;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.dto.PasswordVerificationResult;
import com.anterka.closeauthbackend.identity.dto.PasswordVerificationResult.FailureReason;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.entity.UserIdentity;
import com.anterka.closeauthbackend.identity.enums.IdpType;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.repository.UserRepository;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity domain service (Section 7.4). Owns users and their credential identities within a tenant,
 * following the Stage 3a conventions (method-level {@code @Transactional}, {@code TenantContext} for
 * within-tenant ops, {@code CommandValidator} first, domain exceptions, DTO boundary, tenant guard).
 *
 * <p>Credentials never live on the user row: a password-based user is a {@link User} plus a
 * {@link UserIdentity} with {@code idp_type = LOCAL_PASSWORD} holding the hash. The code does not
 * assume exactly one identity or that it is local — it selects the {@code LOCAL_PASSWORD} identity
 * explicitly, leaving room for social/federated identities (Phase 2) on the same user.
 *
 * <p>Email is normalized to <b>trimmed, lowercased</b> form before every uniqueness check, storage,
 * and lookup, so {@code Alice@X.com} and {@code alice@x.com} are the same user within a tenant.
 */
@Service
public class UserService {

    /** Non-secret constant used only to burn comparable time on the "no user/identity" path. */
    private static final String TIMING_GUARD_RAW = "timing-guard-not-a-real-password";

    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final PasswordHasher passwordHasher;
    private final CommandValidator commandValidator;
    private final UserStateMachine userStateMachine;
    /** Extension seam beans (empty in 3b; 3c adds default-role assignment). */
    private final List<UserProvisioningCallback> provisioningCallbacks;
    /** 7b integration: the last-admin invariant (3c-ii) + token revocation (4b-ii) on deactivation/deletion. */
    private final TenantRoleService tenantRoleService;
    private final TokenRevocationService tokenRevocationService;
    private final AuditEmitter auditEmitter;

    /** Precomputed dummy hash for constant-ish-time verification on the user-not-found path. */
    private final String timingGuardHash;

    public UserService(UserRepository userRepository,
                       TenantService tenantService,
                       PasswordHasher passwordHasher,
                       CommandValidator commandValidator,
                       UserStateMachine userStateMachine,
                       List<UserProvisioningCallback> provisioningCallbacks,
                       TenantRoleService tenantRoleService,
                       TokenRevocationService tokenRevocationService,
                       AuditEmitter auditEmitter) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.passwordHasher = passwordHasher;
        this.commandValidator = commandValidator;
        this.userStateMachine = userStateMachine;
        this.provisioningCallbacks = provisioningCallbacks;
        this.tenantRoleService = tenantRoleService;
        this.tokenRevocationService = tokenRevocationService;
        this.auditEmitter = auditEmitter;
        this.timingGuardHash = passwordHasher.hash(TIMING_GUARD_RAW).hash();
    }

    // ---------------------------------------------------------------------
    // Creation (User + LOCAL_PASSWORD identity, atomically)
    // ---------------------------------------------------------------------

    /**
     * Creates a password-based user and its {@code LOCAL_PASSWORD} identity in one transaction.
     * Defaults to status {@code PENDING} unless the command specifies {@code ACTIVE}.
     *
     * @throws EmailAlreadyExistsException if the (normalized) email is taken within the tenant (CONFLICT)
     */
    @Transactional
    public UserView createUserWithPassword(TenantContext context, CreateUserWithPasswordCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);

        UserStatus initialStatus = resolveInitialStatus(command.initialStatus());
        String email = normalizeEmail(command.email());

        if (userRepository.existsByEmailInTenant(email, context.tenantId())) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = new User();
        user.setTenantId(context.tenantId());
        user.setEmail(email);
        user.setFirstName(command.firstName());
        user.setLastName(command.lastName());
        user.setPhone(command.phone());
        user.setStatus(initialStatus);
        User saved = userRepository.save(user);

        // Credentials live in user_identities, not on the user row.
        buildLocalPasswordIdentity(saved, command.password());

        // --- STAGE 3c extension seam ---
        // 3c contributes a UserProvisioningCallback @Component that assigns the tenant's default
        // role(s) to the new user, within this same transaction. No-op in 3b (no implementations).
        for (UserProvisioningCallback callback : provisioningCallbacks) {
            callback.onUserProvisioned(saved, context);
        }

        // createdBy is left to actor-enrichment: an admin JWT → actor set (admin-created); no principal (a public
        // self-registration endpoint) → no actor (self-registered). The actor field itself distinguishes the two.
        auditEmitter.emit(AuditEvents.userCreated(context.tenantId(), saved.getId(), null));
        return UserView.from(saved);
    }

    // ---------------------------------------------------------------------
    // Credential management
    // ---------------------------------------------------------------------

    /**
     * Adds a {@code LOCAL_PASSWORD} identity to an existing user (e.g. a social-only user opting into a
     * password). Multi-identity-aware: fails if a local password already exists.
     *
     * @throws LocalPasswordAlreadySetException if the user already has a local password (CONFLICT)
     */
    @Transactional
    public void addLocalPasswordIdentity(TenantContext context, UUID userId, String rawPassword) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        if (findLocalPasswordIdentity(user).isPresent()) {
            throw new LocalPasswordAlreadySetException(userId);
        }
        buildLocalPasswordIdentity(user, rawPassword);
    }

    /**
     * Authenticated self-service password change: verifies the current password, then replaces it.
     *
     * @throws InvalidCredentialsException if the user has no local password or the current one is wrong
     *         (generic, so it does not reveal which)
     */
    @Transactional
    public void changePassword(TenantContext context, ChangePasswordCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, command.userId());

        UserIdentity identity = findLocalPasswordIdentity(user)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordHasher.matches(command.currentPassword(), identity.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        applyPassword(identity, command.newPassword());
    }

    /**
     * Sets a new local password WITHOUT requiring the current one — the primitive Stage 6's password-reset
     * flow calls after it has validated a reset token. Creates the local identity if the user has none.
     *
     * <p>This primitive performs NO authorization of its own; the caller (Stage 6) is responsible for
     * having proven the reset is legitimate.
     */
    @Transactional
    public void resetLocalPassword(TenantContext context, UUID userId, String newRawPassword) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        Optional<UserIdentity> existing = findLocalPasswordIdentity(user);
        if (existing.isPresent()) {
            applyPassword(existing.get(), newRawPassword);
        } else {
            buildLocalPasswordIdentity(user, newRawPassword);
        }
    }

    /**
     * Reads the credential-lifecycle state of {@code userId}'s {@code LOCAL_PASSWORD} identity (Phase 2 of the
     * tenant-onboarding design, §2.2/§2.3) — the primitive {@code LoginPolicyService}'s shared rotation/expiry gate
     * is built on. Empty if the user has no local password (there is no temp credential to gate — the password
     * path would already have failed with {@code NO_LOCAL_PASSWORD} before this is ever consulted).
     */
    @Transactional(readOnly = true)
    public Optional<LocalCredentialState> getLocalCredentialState(TenantContext context, UUID userId) {
        User user = loadUserOrThrow(context, userId);
        return findLocalPasswordIdentity(user)
                .map(identity -> new LocalCredentialState(identity.isMustChangePassword(),
                        identity.getTempCredentialExpiresAt()));
    }

    /**
     * Completes a forced password rotation (Phase 2, §2.2.2): sets the real password AND clears both
     * credential-lifecycle fields in the same transaction, so a legitimate rotation can never leave the row in an
     * inconsistent state ({@code must_change_password=false} with a stale {@code temp_credential_expires_at}, or the
     * reverse). Deliberately NOT built on {@link #resetLocalPassword} — that primitive is asserted elsewhere to leave
     * these fields untouched (self-service reset is a different scenario with no lifecycle fields to clear).
     *
     * <p>This primitive performs NO authorization of its own; the caller ({@code PasswordRotationService}) is
     * responsible for having proven the rotation is legitimate (a validly consumed {@code TENANT_ADMIN_ONBOARDING}
     * one-time token).
     */
    @Transactional
    public void completeForcedRotation(TenantContext context, UUID userId, String newRawPassword) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        UserIdentity identity = findLocalPasswordIdentity(user)
                .orElseThrow(InvalidCredentialsException::new);
        applyPassword(identity, newRawPassword);
        identity.setMustChangePassword(false);
        identity.setTempCredentialExpiresAt(null);
    }

    /**
     * Issues a system-generated temporary credential (Phase 3 of the tenant-onboarding design,
     * {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md} §2.4/§2.9): sets the password AND both credential-lifecycle
     * fields in one call, so the row can never be left half-set (a temp password with no expiry, or an expiry
     * left on what is actually a user-chosen password). The exact inverse of {@link #completeForcedRotation}.
     *
     * <p>On the tenant-admin bootstrap path the caller ({@code TenantOnboardingService}) has just hashed the
     * same raw password once already via {@link #createUserWithPassword} — this hashes it again rather than
     * exposing a second, flags-only primitive that could accidentally mark an existing, user-chosen password as
     * temporary. One extra hash on a rare, admin-only operation is the cheaper and safer tradeoff.
     *
     * <p>This primitive performs NO authorization of its own; the caller is responsible for having proven the
     * issuance is legitimate (a platform-admin-gated bootstrap/reissue operation).
     */
    @Transactional
    public void issueTempCredential(TenantContext context, UUID userId, String rawTempPassword, Instant expiresAt) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        UserIdentity identity = findLocalPasswordIdentity(user)
                .orElseThrow(InvalidCredentialsException::new);
        applyPassword(identity, rawTempPassword);
        identity.setMustChangePassword(true);
        identity.setTempCredentialExpiresAt(expiresAt);
    }

    /**
     * Verification primitive for Stage 6's login flow (Concern 4). Resolves the user by email within the
     * tenant, finds their {@code LOCAL_PASSWORD} identity, and checks the password.
     *
     * <p><b>Enumeration-safe:</b> never throws and returns a uniform {@link PasswordVerificationResult} —
     * on any failure {@code success == false} and {@code user == null}; the {@link FailureReason} is for
     * server-side audit only. A dummy hash comparison runs on the no-user / no-identity paths to reduce
     * timing signal. This primitive does NOT gate on tenant status or user status other than treating a
     * {@code DELETED} user as absent — those policy checks belong to Stage 6.
     */
    @Transactional(readOnly = true)
    public PasswordVerificationResult verifyPassword(TenantContext context, String email, String rawPassword) {
        String normalized = normalizeEmail(email);
        Optional<User> maybeUser = userRepository.findByEmailInTenant(normalized, context.tenantId());

        if (maybeUser.isEmpty() || maybeUser.get().getStatus() == UserStatus.DELETED) {
            passwordHasher.matches(rawPassword, timingGuardHash); // equalize timing
            return PasswordVerificationResult.failure(FailureReason.USER_NOT_FOUND);
        }

        User user = maybeUser.get();
        Optional<UserIdentity> localIdentity = findLocalPasswordIdentity(user);
        if (localIdentity.isEmpty()) {
            passwordHasher.matches(rawPassword, timingGuardHash); // equalize timing
            return PasswordVerificationResult.failure(FailureReason.NO_LOCAL_PASSWORD);
        }

        if (passwordHasher.matches(rawPassword, localIdentity.get().getPasswordHash())) {
            return PasswordVerificationResult.success(UserView.from(user));
        }
        return PasswordVerificationResult.failure(FailureReason.BAD_PASSWORD);
    }

    // ---------------------------------------------------------------------
    // Verification-flag primitives (Stage 6 flips these on OTP success)
    // ---------------------------------------------------------------------

    @Transactional
    public UserView markEmailVerified(TenantContext context, UUID userId) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        user.setEmailVerified(true);
        return UserView.from(user);
    }

    @Transactional
    public UserView markPhoneVerified(TenantContext context, UUID userId) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        user.setPhoneVerified(true);
        return UserView.from(user);
    }

    // ---------------------------------------------------------------------
    // Status lifecycle (soft-delete only; users has no deleted_at column)
    // ---------------------------------------------------------------------

    /** {@code PENDING → ACTIVE} or {@code SUSPENDED → ACTIVE}. */
    @Transactional
    public UserView activateUser(TenantContext context, UUID userId) {
        return transition(context, userId, UserStatus.ACTIVE);
    }

    /** {@code ACTIVE → SUSPENDED}. */
    @Transactional
    public UserView suspendUser(TenantContext context, UUID userId) {
        return transition(context, userId, UserStatus.SUSPENDED);
    }

    /** Soft delete → {@code DELETED} (terminal). The row is retained; users has no {@code deleted_at}. */
    @Transactional
    public UserView deleteUser(TenantContext context, UUID userId) {
        return transition(context, userId, UserStatus.DELETED);
    }

    private UserView transition(TenantContext context, UUID userId, UserStatus target) {
        tenantService.requireActiveTenant(context);
        User user = loadUserOrThrow(context, userId);
        userStateMachine.checkTransition(user.getStatus(), target);
        boolean deactivating = target == UserStatus.SUSPENDED || target == UserStatus.DELETED;
        if (deactivating && tenantRoleService.isLastTenantAdmin(context, userId)) {
            // Last-admin invariant (3c-ii): refuse an operation that would orphan the tenant of its only TENANT_ADMIN.
            throw new LastTenantAdminException(context.tenantId());
        }
        user.setStatus(target);
        if (deactivating) {
            // 4b-ii: kill the user's live access tokens now, not merely at expiry (parallel to 7a's platform-admin path).
            tokenRevocationService.revokeAllUserTokens(context.tenantId(), userId);
        }
        auditEmitter.emit(switch (target) {
            case SUSPENDED -> AuditEvents.userSuspended(context.tenantId(), userId);
            case DELETED -> AuditEvents.userDeleted(context.tenantId(), userId);
            case ACTIVE -> AuditEvents.userActivated(context.tenantId(), userId);
            default -> throw new IllegalStateException("Unexpected user transition target: " + target);
        });
        return UserView.from(user);
    }

    // ---------------------------------------------------------------------
    // Lookups (tenant-scoped; reads do not require the tenant to be ACTIVE)
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public UserView getUserById(TenantContext context, UUID userId) {
        return UserView.from(loadUserOrThrow(context, userId));
    }

    /**
     * Resolves the {@code idp_type} of the identity a user authenticates with (Stage 6a — feeds the token {@code idp}
     * claim, §12, replacing 4a's hardcoded {@code LOCAL_PASSWORD} default). In 6a users authenticate via their
     * {@code LOCAL_PASSWORD} identity, which is preferred here; if absent, the user's first identity is used.
     * When multiple federated identities per user arrive (Phase 2), the specific identity used to authenticate is
     * login-recorded and threaded through instead of re-derived here.
     */
    @Transactional(readOnly = true)
    public Optional<IdpType> getAuthenticatingIdpType(TenantContext context, UUID userId) {
        return userRepository.findByIdInTenant(userId, context.tenantId())
                .flatMap(user -> findLocalPasswordIdentity(user)
                        .or(() -> user.getIdentities().stream().findFirst()))
                .map(UserIdentity::getIdpType);
    }

    @Transactional(readOnly = true)
    public UserView getUserByEmail(TenantContext context, String email) {
        String normalized = normalizeEmail(email);
        User user = userRepository.findByEmailInTenant(normalized, context.tenantId())
                .orElseThrow(() -> new UserNotFoundException("email", normalized));
        return UserView.from(user);
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(TenantContext context, String email) {
        return userRepository.existsByEmailInTenant(normalizeEmail(email), context.tenantId());
    }

    @Transactional(readOnly = true)
    public List<UserView> listUsers(TenantContext context) {
        return userRepository.findByTenantId(context.tenantId()).stream()
                .map(UserView::from)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Normalization rule: trim surrounding whitespace and lowercase (root locale). */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private UserStatus resolveInitialStatus(UserStatus requested) {
        UserStatus status = requested == null ? UserStatus.PENDING : requested;
        if (status != UserStatus.PENDING && status != UserStatus.ACTIVE) {
            throw new CloseAuthDomainException(ErrorCategory.VALIDATION, "user.invalid_initial_status",
                    "Initial user status must be PENDING or ACTIVE, was " + status);
        }
        return status;
    }

    private User loadUserOrThrow(TenantContext context, UUID userId) {
        return userRepository.findByIdInTenant(userId, context.tenantId())
                .orElseThrow(() -> new UserNotFoundException("id", userId));
    }

    private Optional<UserIdentity> findLocalPasswordIdentity(User user) {
        return user.getIdentities().stream()
                .filter(i -> i.getIdpType() == IdpType.LOCAL_PASSWORD)
                .findFirst();
    }

    private void buildLocalPasswordIdentity(User user, String rawPassword) {
        HashedPassword hashed = passwordHasher.hash(rawPassword);
        UserIdentity identity = new UserIdentity();
        identity.setUser(user);
        identity.setTenantId(user.getTenantId());
        identity.setIdpType(IdpType.LOCAL_PASSWORD);
        identity.setIdpSubject(null); // local passwords have no upstream subject
        identity.setPasswordHash(hashed.hash());
        identity.setPasswordAlgo(hashed.algorithm());
        // Maintain both sides; the User @OneToMany cascade persists the new identity on flush.
        user.getIdentities().add(identity);
    }

    private void applyPassword(UserIdentity identity, String rawPassword) {
        HashedPassword hashed = passwordHasher.hash(rawPassword);
        identity.setPasswordHash(hashed.hash());
        identity.setPasswordAlgo(hashed.algorithm());
    }
}

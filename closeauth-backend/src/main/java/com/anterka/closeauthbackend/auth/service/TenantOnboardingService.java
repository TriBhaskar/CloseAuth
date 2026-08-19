package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.BootstrapAdminCommand;
import com.anterka.closeauthbackend.auth.dto.CreateUserWithTempCredentialCommand;
import com.anterka.closeauthbackend.auth.dto.TempCredentialReissuedView;
import com.anterka.closeauthbackend.auth.dto.TenantAdminBootstrappedView;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.client.service.AdminConsoleClientProvisioningCallback;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.exception.NoPendingTempCredentialException;
import com.anterka.closeauthbackend.common.exception.TenantAdminAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.TenantRoleNotFoundException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.service.SystemRoleNames;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-admin onboarding issuance (Phase 3 of the tenant-onboarding design, {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md}
 * §2.4–§2.8, §2.9). This is the first code that SETS {@code user_identities.must_change_password} — Phases 0–2
 * already built the complete enforcement path (login rate limiting, the schema, the rotation gate on both login
 * methods, {@link PasswordRotationService}, {@code /password-rotation/confirm}) against a row nothing in the
 * product could create until now.
 *
 * <p>Sibling to {@link InviteService} and {@link PasswordRotationService} — calls {@link UserService}/
 * {@link TenantRoleService} directly, exactly as the tenant-provisioning callbacks do (§1.2), rather than routing
 * through the public {@code /v1/tenants/{id}/**} HTTP surface. Lives in {@code auth}, not {@code tenant}: {@code auth}
 * already depends on both {@code identity} and {@code tenant}, and gains only {@code auth → rbac} here, which is a
 * safe new edge ({@code rbac} does not depend on {@code auth}). Putting this in {@code tenant} would create a cycle
 * against {@code rbac.service.RoleStarterPackProvisioningCallback} ({@code rbac → tenant}).
 *
 * <h2>Atomicity (§2.5)</h2>
 * Both operations are single {@code @Transactional} methods. The dangerous half-state this exists to prevent — an
 * active user holding only {@code TENANT_MEMBER} because the {@code TENANT_ADMIN} grant failed after user-creation
 * committed — is unobservable: a failure anywhere rolls the whole thing back, including the audit outbox row
 * ({@code AuditOutboxWriter} listens at {@code BEFORE_COMMIT}, so it rolls back with the business action).
 *
 * <h2>Email inside the transaction (deliberate)</h2>
 * {@link AuthNotificationSender#sendTenantAdminOnboardingLink} is called unguarded, inside the transaction — an SMTP
 * failure rolls back the whole bootstrap/reissue. This follows {@link InviteService#issueInvite}, the only other
 * admin-triggered (non-self-service) email in the codebase and the exact analogue: the self-service flows'
 * enumeration-safety reason for swallowing delivery failures doesn't apply here (the caller just created the
 * account, there's nothing to enumerate), and a bootstrap whose email silently never arrived is a worse outcome
 * than a clear error the platform admin can retry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantOnboardingService {

    private final TenantService tenantService;
    private final UserService userService;
    private final TenantRoleService tenantRoleService;
    private final TenantRoleRepository tenantRoleRepository;
    private final PasswordRotationService passwordRotationService;
    private final OneTimeTokenGenerator oneTimeTokenGenerator;
    private final AuthNotificationSender notifier;
    private final CommandValidator commandValidator;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /**
     * Creates a tenant's first {@code TENANT_ADMIN}: a new {@code ACTIVE} user with a system-generated temporary
     * credential, granted {@code TENANT_ADMIN}, emailed a rotation link. Refuses (409,
     * {@link TenantAdminAlreadyExistsException}) if the tenant already has an active admin — bootstrap is a
     * first-admin-only operation; additional admins go through the existing, ungated tenant-role grant path (§1.9).
     *
     * @throws com.anterka.closeauthbackend.common.exception.TenantSuspendedException if the tenant is not ACTIVE
     *         (bootstrap deliberately does NOT bypass this guard the way the provisioning callbacks do — see the
     *         class javadoc; a PROVISIONING tenant must be activated first)
     */
    @Transactional
    public TenantAdminBootstrappedView bootstrapFirstAdmin(UUID tenantId, BootstrapAdminCommand command) {
        commandValidator.validate(command);
        TenantContext context = TenantContext.of(tenantId);
        TenantView tenant = tenantService.requireActiveTenant(context);

        if (tenantRoleService.countActiveTenantAdmins(context) > 0) {
            throw new TenantAdminAlreadyExistsException(tenantId);
        }

        String tempPassword = generateTempPassword();
        Instant expiresAt = Instant.now().plus(properties.getOneTimeToken().getTenantAdminOnboardingTtl());

        UserView user = userService.createUserWithPassword(context, new CreateUserWithPasswordCommand(
                command.email(), tempPassword, command.firstName(), command.lastName(), null, UserStatus.ACTIVE));
        // Bundles must_change_password=true + the expiry with the SAME raw password just hashed above by
        // createUserWithPassword — see UserService.issueTempCredential's javadoc for why this hashes twice
        // rather than exposing a flags-only primitive.
        userService.issueTempCredential(context, user.id(), tempPassword, expiresAt);
        assignTenantAdmin(context, user.id());

        String onboardingUrl = passwordRotationService.beginRotation(
                context, user.id(), user.email(), adminConsoleClientId(tenant.slug()), null, tenant.slug());
        notifier.sendTenantAdminOnboardingLink(user.email(), onboardingUrl, tenant.name());

        auditEmitter.emit(AuditEvents.tempCredentialIssued(tenantId, user.id(), expiresAt));
        log.info("Tenant-admin bootstrap: tenant={} user={} expiresAt={} (temp password NOT logged)",
                tenantId, user.id(), expiresAt);
        return new TenantAdminBootstrappedView(user, tempPassword, expiresAt);
    }

    /**
     * FE-4a: tenant-admin-scoped sibling of {@link #bootstrapFirstAdmin} — creates an ORDINARY tenant user (not
     * necessarily an admin, and with no "already has an admin" refusal) with a system-generated temporary
     * credential, per spec §6.4.2's temporary-password create mode. Deliberately does NOT send an onboarding email
     * or resolve a client id: {@code must_change_password=true} alone is sufficient, since whichever client the
     * user next signs in through already carries its own {@code client_id} for {@code LoginController} to route
     * the forced-rotation redirect with. The optional initial role is assigned in the same transaction as
     * creation, so the caller never observes "user created, role grant failed" as a half-state.
     */
    @Transactional
    public TenantAdminBootstrappedView createUserWithTempCredential(TenantContext context,
                                                                     CreateUserWithTempCredentialCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);

        String tempPassword = generateTempPassword();
        Instant expiresAt = Instant.now().plus(properties.getOneTimeToken().getTenantAdminOnboardingTtl());

        UserView user = userService.createUserWithPassword(context, new CreateUserWithPasswordCommand(
                command.email(), tempPassword, command.firstName(), command.lastName(), command.phone(),
                UserStatus.ACTIVE));
        // Bundles must_change_password=true + the expiry with the SAME raw password just hashed above — see
        // UserService.issueTempCredential's javadoc for why this hashes twice rather than exposing a flags-only
        // primitive (same pairing bootstrapFirstAdmin uses above).
        userService.issueTempCredential(context, user.id(), tempPassword, expiresAt);

        if (command.initialRoleId() != null) {
            tenantRoleService.assignTenantRole(context, user.id(), command.initialRoleId(), null);
        }

        auditEmitter.emit(AuditEvents.tempCredentialIssued(context.tenantId(), user.id(), expiresAt));
        log.info("Tenant user created with temp credential: tenant={} user={} expiresAt={} (temp password NOT logged)",
                context.tenantId(), user.id(), expiresAt);
        return new TenantAdminBootstrappedView(user, tempPassword, expiresAt);
    }

    /**
     * Reissues a fresh temporary credential for an existing user whose onboarding credential never got used —
     * recovery for an expired or lost temp password/link. Refuses (409, {@link NoPendingTempCredentialException})
     * if the user has no {@code LOCAL_PASSWORD} identity or has already completed rotation
     * ({@code mustChangePassword == false}): reissue is deliberately NOT a way for platform staff to force a
     * working admin account back into forced rotation (§2.9 decision) — an already-rotated admin who is genuinely
     * locked out uses ordinary self-service password reset instead, which does not re-force rotation.
     */
    @Transactional
    public TempCredentialReissuedView reissueOnboardingCredential(UUID tenantId, UUID userId) {
        TenantContext context = TenantContext.of(tenantId);
        TenantView tenant = tenantService.requireActiveTenant(context);
        UserView user = userService.getUserById(context, userId);

        Optional<LocalCredentialState> state = userService.getLocalCredentialState(context, userId);
        if (state.isEmpty() || !state.get().mustChangePassword()) {
            throw new NoPendingTempCredentialException(userId);
        }

        String tempPassword = generateTempPassword();
        Instant expiresAt = Instant.now().plus(properties.getOneTimeToken().getTenantAdminOnboardingTtl());

        userService.issueTempCredential(context, userId, tempPassword, expiresAt);
        // beginRotation invalidates any outstanding onboarding token for this user BEFORE minting the fresh one
        // (§2.3's one-live-token-per-user rule) — the same "invalidate, then issue" pairing bootstrap uses above.
        String onboardingUrl = passwordRotationService.beginRotation(
                context, userId, user.email(), adminConsoleClientId(tenant.slug()), null, tenant.slug());
        notifier.sendTenantAdminOnboardingLink(user.email(), onboardingUrl, tenant.name());

        auditEmitter.emit(AuditEvents.tempCredentialReissued(tenantId, userId, expiresAt));
        log.info("Tenant-admin credential reissued: tenant={} user={} expiresAt={} (temp password NOT logged)",
                tenantId, userId, expiresAt);
        return new TempCredentialReissuedView(userId, tempPassword, expiresAt);
    }

    private void assignTenantAdmin(TenantContext context, UUID userId) {
        TenantRole adminRole = tenantRoleRepository.findByTenantIdAndName(context.tenantId(), SystemRoleNames.TENANT_ADMIN)
                .orElseThrow(() -> new TenantRoleNotFoundException("name", SystemRoleNames.TENANT_ADMIN));
        // assignedByUserId=null: the actor is a platform admin (no tenant-user identity), not a tenant user —
        // matches TenantRoleController's own call for the same reason; audit actor-enrichment fills it from the
        // platform-admin JWT.
        tenantRoleService.assignTenantRole(context, userId, adminRole.getId(), null);
    }

    /** Mirrors {@code ClientSecretGenerator}'s exact convention (SecureRandom, 32 bytes, unpadded base64url). */
    private String generateTempPassword() {
        return oneTimeTokenGenerator.generate(OneTimeTokenFormat.OPAQUE_LINK, 0);
    }

    private String adminConsoleClientId(String tenantSlug) {
        return AdminConsoleClientProvisioningCallback.CLIENT_ID_PREFIX + tenantSlug;
    }
}

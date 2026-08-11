package com.anterka.closeauthbackend.identity.service;

import com.anterka.closeauthbackend.common.exception.EmailAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.exception.InvalidUserStateTransitionException;
import com.anterka.closeauthbackend.common.security.PasswordHasher;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.PasswordVerificationResult;
import com.anterka.closeauthbackend.identity.dto.PasswordVerificationResult.FailureReason;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.entity.User;
import com.anterka.closeauthbackend.identity.entity.UserIdentity;
import com.anterka.closeauthbackend.identity.enums.IdpType;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.repository.UserRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private final TenantContext ctxA = TenantContext.of(TENANT_A);
    private final TenantContext ctxB = TenantContext.of(TENANT_B);

    private UserRepository userRepository;
    private PasswordHasher hasher;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class); // requireActiveTenant → null (no-op)
        // Fast bcrypt for tests.
        PasswordEncoder encoder = new DelegatingPasswordEncoder(
                "bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(4)));
        hasher = new PasswordHasher(encoder);
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        // 7b: last-admin guard + token revocation on deactivation. Mocks default to "not last admin" / no-op revoke,
        // so existing status-transition tests are unaffected; dedicated 7b tests cover the wired behavior.
        com.anterka.closeauthbackend.rbac.service.TenantRoleService tenantRoleService =
                Mockito.mock(com.anterka.closeauthbackend.rbac.service.TenantRoleService.class);
        com.anterka.closeauthbackend.token.service.TokenRevocationService tokenRevocationService =
                Mockito.mock(com.anterka.closeauthbackend.token.service.TokenRevocationService.class);
        userService = new UserService(userRepository, tenantService, hasher, commandValidator,
                new UserStateMachine(), List.of(), tenantRoleService, tokenRevocationService,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
    }

    private CreateUserWithPasswordCommand command(String email) {
        return new CreateUserWithPasswordCommand(email, "password123", "First", "Last", null, null);
    }

    // ---- Per-tenant email uniqueness --------------------------------------

    @Test
    void duplicateEmailWithinTenantThrowsConflict() {
        when(userRepository.existsByEmailInTenant("alice@x.com", TENANT_A)).thenReturn(true);

        assertThatThrownBy(() -> userService.createUserWithPassword(ctxA, command("alice@x.com")))
                .isInstanceOfSatisfying(EmailAlreadyExistsException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(ErrorCategory.CONFLICT));
        verify(userRepository, never()).save(any());
    }

    @Test
    void sameEmailInDifferentTenantIsAllowed() {
        when(userRepository.existsByEmailInTenant("alice@x.com", TENANT_B)).thenReturn(false);

        UserView view = userService.createUserWithPassword(ctxB, command("alice@x.com"));

        assertThat(view.email()).isEqualTo("alice@x.com");
        assertThat(view.tenantId()).isEqualTo(TENANT_B);
        assertThat(view.status()).isEqualTo(UserStatus.PENDING);
    }

    // ---- Email normalization ----------------------------------------------

    @Test
    void emailIsNormalizedToLowercaseForUniquenessAndStorage() {
        when(userRepository.existsByEmailInTenant("alice@x.com", TENANT_A)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        userService.createUserWithPassword(ctxA, command("Alice@X.com"));

        verify(userRepository).existsByEmailInTenant("alice@x.com", TENANT_A);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@x.com");
    }

    @Test
    void caseDifferingEmailsCollideWithinTenant() {
        // An existing "alice@x.com" makes a new "Alice@X.com" a conflict (same normalized value).
        when(userRepository.existsByEmailInTenant("alice@x.com", TENANT_A)).thenReturn(true);

        assertThatThrownBy(() -> userService.createUserWithPassword(ctxA, command("Alice@X.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    // ---- Credentials in user_identities, hashed ---------------------------

    @Test
    void createStoresHashedLocalPasswordIdentityNotPlaintext() {
        when(userRepository.existsByEmailInTenant("bob@x.com", TENANT_A)).thenReturn(false);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        userService.createUserWithPassword(ctxA,
                new CreateUserWithPasswordCommand("bob@x.com", "s3cretpassword", null, null, null, null));

        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getIdentities()).hasSize(1);
        UserIdentity identity = saved.getIdentities().get(0);
        assertThat(identity.getIdpType()).isEqualTo(IdpType.LOCAL_PASSWORD);
        assertThat(identity.getIdpSubject()).isNull();
        assertThat(identity.getPasswordHash())
                .isNotNull()
                .startsWith("{bcrypt}")
                .doesNotContain("s3cretpassword");
        assertThat(identity.getPasswordAlgo()).isEqualTo("bcrypt");
        assertThat(hasher.matches("s3cretpassword", identity.getPasswordHash())).isTrue();
        // Phase 1 (tenant-admin onboarding, inert schema): a normally-created identity defaults to
        // no-rotation-pending / no expiry — nothing sets these yet, so a plain create must leave them alone.
        assertThat(identity.isMustChangePassword()).isFalse();
        assertThat(identity.getTempCredentialExpiresAt()).isNull();
    }

    // ---- Phase 1 (tenant-admin onboarding, inert schema): must_change_password / temp_credential_expires_at
    // are untouched by every EXISTING password-mutation path. Nothing issues a temp credential yet, so the only
    // way either field is ever true/non-null today is a fixture that sets it directly, as below.

    @Test
    void resetLocalPasswordDoesNotTouchCredentialLifecycleFields() {
        User user = userWithLocalPassword("dana@x.com", "old-pass", UserStatus.ACTIVE);
        UserIdentity identity = user.getIdentities().get(0);
        identity.setMustChangePassword(true);
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
        identity.setTempCredentialExpiresAt(expiry);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));

        userService.resetLocalPassword(ctxA, user.getId(), "brand-new-password");

        assertThat(identity.isMustChangePassword())
                .as("resetLocalPassword must not clear/set the rotation flag — Phase 1 is inert").isTrue();
        assertThat(identity.getTempCredentialExpiresAt())
                .as("resetLocalPassword must not touch the temp-credential expiry — Phase 1 is inert").isEqualTo(expiry);
    }

    @Test
    void changePasswordDoesNotTouchCredentialLifecycleFields() {
        User user = userWithLocalPassword("erin@x.com", "old-pass", UserStatus.ACTIVE);
        UserIdentity identity = user.getIdentities().get(0);
        identity.setMustChangePassword(true);
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
        identity.setTempCredentialExpiresAt(expiry);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));

        userService.changePassword(ctxA,
                new com.anterka.closeauthbackend.identity.dto.ChangePasswordCommand(
                        user.getId(), "old-pass", "brand-new-password"));

        assertThat(identity.isMustChangePassword())
                .as("changePassword must not clear/set the rotation flag — Phase 1 is inert").isTrue();
        assertThat(identity.getTempCredentialExpiresAt())
                .as("changePassword must not touch the temp-credential expiry — Phase 1 is inert").isEqualTo(expiry);
    }

    // ---- Phase 2 (forced credential rotation): getLocalCredentialState / completeForcedRotation ------------------

    @Test
    void getLocalCredentialStateReturnsCurrentFlags() {
        User user = userWithLocalPassword("frank@x.com", "temp-pass", UserStatus.ACTIVE);
        UserIdentity identity = user.getIdentities().get(0);
        identity.setMustChangePassword(true);
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
        identity.setTempCredentialExpiresAt(expiry);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));

        var state = userService.getLocalCredentialState(ctxA, user.getId());

        assertThat(state).isPresent();
        assertThat(state.get().mustChangePassword()).isTrue();
        assertThat(state.get().tempCredentialExpiresAt()).isEqualTo(expiry);
    }

    @Test
    void getLocalCredentialStateEmptyWhenNoLocalPasswordIdentity() {
        User user = userWithStatus(UserStatus.ACTIVE);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));

        assertThat(userService.getLocalCredentialState(ctxA, user.getId())).isEmpty();
    }

    @Test
    void completeForcedRotationSetsPasswordAndClearsBothLifecycleFields() {
        User user = userWithLocalPassword("grace@x.com", "temp-pass", UserStatus.ACTIVE);
        UserIdentity identity = user.getIdentities().get(0);
        identity.setMustChangePassword(true);
        identity.setTempCredentialExpiresAt(java.time.Instant.now().plusSeconds(3600));
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));

        userService.completeForcedRotation(ctxA, user.getId(), "brand-new-real-password");

        assertThat(identity.isMustChangePassword()).as("rotation must clear the flag").isFalse();
        assertThat(identity.getTempCredentialExpiresAt()).as("rotation must clear the expiry").isNull();
        assertThat(hasher.matches("brand-new-real-password", identity.getPasswordHash())).isTrue();
        assertThat(hasher.matches("temp-pass", identity.getPasswordHash()))
                .as("the old temp password must no longer match").isFalse();
    }

    // ---- verifyPassword primitive + enumeration-safety --------------------

    @Test
    void verifyPasswordSucceedsForCorrectPassword() {
        User user = userWithLocalPassword("carol@x.com", "correct-pass", UserStatus.ACTIVE);
        when(userRepository.findByEmailInTenant("carol@x.com", TENANT_A)).thenReturn(Optional.of(user));

        PasswordVerificationResult result = userService.verifyPassword(ctxA, "carol@x.com", "correct-pass");

        assertThat(result.success()).isTrue();
        assertThat(result.user()).isNotNull();
        assertThat(result.user().email()).isEqualTo("carol@x.com");
    }

    @Test
    void verifyPasswordEnumerationSafetyWrongPasswordAndNoUserLookIdentical() {
        User user = userWithLocalPassword("carol@x.com", "correct-pass", UserStatus.ACTIVE);
        when(userRepository.findByEmailInTenant("carol@x.com", TENANT_A)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailInTenant("ghost@x.com", TENANT_A)).thenReturn(Optional.empty());

        PasswordVerificationResult wrongPassword = userService.verifyPassword(ctxA, "carol@x.com", "WRONG");
        PasswordVerificationResult noSuchUser = userService.verifyPassword(ctxA, "ghost@x.com", "whatever1");

        // Surfaced shape must be indistinguishable: both failed, neither carries a user.
        assertThat(wrongPassword.success()).isFalse();
        assertThat(wrongPassword.user()).isNull();
        assertThat(noSuchUser.success()).isFalse();
        assertThat(noSuchUser.user()).isNull();
        // Internal reasons differ (for audit only), proving the service CAN tell them apart.
        assertThat(wrongPassword.failureReason()).isEqualTo(FailureReason.BAD_PASSWORD);
        assertThat(noSuchUser.failureReason()).isEqualTo(FailureReason.USER_NOT_FOUND);
    }

    @Test
    void verifyPasswordTreatsDeletedUserAsAbsent() {
        User user = userWithLocalPassword("gone@x.com", "correct-pass", UserStatus.DELETED);
        when(userRepository.findByEmailInTenant("gone@x.com", TENANT_A)).thenReturn(Optional.of(user));

        PasswordVerificationResult result = userService.verifyPassword(ctxA, "gone@x.com", "correct-pass");

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo(FailureReason.USER_NOT_FOUND);
    }

    // ---- Status transitions via the service -------------------------------

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void activateUser(UserStatus from) {
        User user = userWithStatus(from);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));
        boolean valid = from == UserStatus.PENDING || from == UserStatus.SUSPENDED;

        if (valid) {
            assertThat(userService.activateUser(ctxA, user.getId()).status()).isEqualTo(UserStatus.ACTIVE);
        } else {
            assertThatThrownBy(() -> userService.activateUser(ctxA, user.getId()))
                    .isInstanceOf(InvalidUserStateTransitionException.class);
            assertThat(user.getStatus()).isEqualTo(from);
        }
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void suspendUser(UserStatus from) {
        User user = userWithStatus(from);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));
        boolean valid = from == UserStatus.ACTIVE;

        if (valid) {
            assertThat(userService.suspendUser(ctxA, user.getId()).status()).isEqualTo(UserStatus.SUSPENDED);
        } else {
            assertThatThrownBy(() -> userService.suspendUser(ctxA, user.getId()))
                    .isInstanceOf(InvalidUserStateTransitionException.class);
            assertThat(user.getStatus()).isEqualTo(from);
        }
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void deleteUser(UserStatus from) {
        User user = userWithStatus(from);
        when(userRepository.findByIdInTenant(user.getId(), TENANT_A)).thenReturn(Optional.of(user));
        boolean valid = from != UserStatus.DELETED;

        if (valid) {
            assertThat(userService.deleteUser(ctxA, user.getId()).status()).isEqualTo(UserStatus.DELETED);
        } else {
            assertThatThrownBy(() -> userService.deleteUser(ctxA, user.getId()))
                    .isInstanceOf(InvalidUserStateTransitionException.class);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private User userWithStatus(UserStatus status) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setTenantId(TENANT_A);
        u.setEmail("x@x.com");
        u.setStatus(status);
        return u;
    }

    private User userWithLocalPassword(String email, String rawPassword, UserStatus status) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setTenantId(TENANT_A);
        u.setEmail(email);
        u.setStatus(status);
        UserIdentity id = new UserIdentity();
        id.setUser(u);
        id.setTenantId(TENANT_A);
        id.setIdpType(IdpType.LOCAL_PASSWORD);
        PasswordHasher.HashedPassword hp = hasher.hash(rawPassword);
        id.setPasswordHash(hp.hash());
        id.setPasswordAlgo(hp.algorithm());
        u.getIdentities().add(id);
        return u;
    }
}

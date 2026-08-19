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
import com.anterka.closeauthbackend.platform.entity.PlatformAdmin;
import com.anterka.closeauthbackend.platform.enums.PlatformAdminStatus;
import com.anterka.closeauthbackend.platform.repository.PlatformAdminRepository;
import com.anterka.closeauthbackend.platform.repository.PlatformAdminRoleRepository;
import com.anterka.closeauthbackend.rbac.entity.PlatformRole;
import com.anterka.closeauthbackend.rbac.repository.PlatformRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the platform-admin service — the security-critical bit being {@link
 * PlatformAdminService#authenticate} which must be <b>enumeration-safe</b>: a uniform {@link PlatformAdminAuthResult}
 * regardless of failure factor, and a timing-guard hash on the no-admin path so absence isn't observable by latency.
 */
@ExtendWith(MockitoExtension.class)
class PlatformAdminServiceTest {

    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private PlatformAdminRoleRepository platformAdminRoleRepository;
    @Mock private PlatformRoleRepository platformRoleRepository;
    @Mock private PasswordHasher passwordHasher;
    @Mock private CommandValidator commandValidator;
    @Mock private com.anterka.closeauthbackend.token.service.TokenRevocationService tokenRevocationService;

    private PlatformAdminService service;

    @BeforeEach
    void setUp() {
        // The constructor pre-computes a timing-guard hash from the hasher.
        lenient().when(passwordHasher.hash(anyString())).thenReturn(new HashedPassword("$2a$guard", "bcrypt"));
        service = new PlatformAdminService(platformAdminRepository, platformAdminRoleRepository,
                platformRoleRepository, passwordHasher, commandValidator, tokenRevocationService,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
    }

    // ---- authenticate: enumeration-safe -----------------------------------

    @Test
    void authenticateUnknownEmailBurnsTimingAndReportsNotFound() {
        when(platformAdminRepository.findByEmail("ghost@x.io")).thenReturn(Optional.empty());

        PlatformAdminAuthResult result = service.authenticate("Ghost@X.io", "whatever");

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(FailureReason.NOT_FOUND);
        assertThat(result.adminId()).isNull();
        // Timing equalization: the hasher is still invoked on the no-admin path.
        verify(passwordHasher).matches(eq("whatever"), anyString());
    }

    @Test
    void authenticateSuspendedAdminIsRejectedWithoutRevealingExistence() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        admin.setStatus(PlatformAdminStatus.SUSPENDED);
        when(platformAdminRepository.findByEmail("boss@x.io")).thenReturn(Optional.of(admin));

        assertRejected(service.authenticate("boss@x.io", "pw"), FailureReason.NOT_ACTIVE);
        verify(passwordHasher).matches(eq("pw"), anyString()); // still burns time
    }

    @Test
    void authenticateWrongPasswordReportsBadPassword() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        when(platformAdminRepository.findByEmail("boss@x.io")).thenReturn(Optional.of(admin));
        when(passwordHasher.matches("wrong", "$2a$real")).thenReturn(false);

        assertRejected(service.authenticate("boss@x.io", "wrong"), FailureReason.BAD_PASSWORD);
    }

    @Test
    void authenticateSuccessSetsLastLoginAndReturnsAdminId() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        when(platformAdminRepository.findByEmail("boss@x.io")).thenReturn(Optional.of(admin));
        when(passwordHasher.matches("right", "$2a$real")).thenReturn(true);

        PlatformAdminAuthResult result = service.authenticate(" Boss@X.io ", "right");

        assertThat(result.success()).isTrue();
        assertThat(result.adminId()).isEqualTo(admin.getId());
        assertThat(admin.getLastLoginAt()).isNotNull();
    }

    // ---- suspend revokes outstanding tokens (§7.8 revocability) ------------

    @Test
    void suspendWritesTheSubKeyedPlatformAdminRevocationMarker() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        when(platformAdminRepository.findById(admin.getId())).thenReturn(java.util.Optional.of(admin));

        service.suspend(admin.getId(), UUID.randomUUID());

        assertThat(admin.getStatus()).isEqualTo(PlatformAdminStatus.SUSPENDED);
        // A compromised/in-flight token must die instantly, not merely at expiry.
        verify(tokenRevocationService).revokePlatformAdminTokens(admin.getId());
    }

    // ---- last-platform-admin protection (IT-9 fix, active-only counting) --

    @Test
    void suspendingTheSoleActivePlatformAdminIsBlocked() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        PlatformRole role = platformAdminRole();
        when(platformAdminRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(platformRoleRepository.findByName("PLATFORM_ADMIN")).thenReturn(Optional.of(role));
        when(platformAdminRoleRepository.isActiveHolder(admin.getId(), role.getId())).thenReturn(true);
        when(platformAdminRoleRepository.countActiveHoldersByRole(role.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.suspend(admin.getId(), UUID.randomUUID()))
                .isInstanceOf(LastPlatformAdminException.class)
                .satisfies(ex -> {
                    assertThat(((CloseAuthDomainException) ex).getCategory()).isEqualTo(ErrorCategory.CONFLICT);
                    assertThat(((CloseAuthDomainException) ex).getCode()).isEqualTo("platform_admin.last_admin");
                });
        assertThat(admin.getStatus()).as("must not mutate on a blocked suspend").isEqualTo(PlatformAdminStatus.ACTIVE);
        verify(tokenRevocationService, never()).revokePlatformAdminTokens(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void suspendingOneOfTwoActivePlatformAdminsSucceeds() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        PlatformRole role = platformAdminRole();
        when(platformAdminRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(platformRoleRepository.findByName("PLATFORM_ADMIN")).thenReturn(Optional.of(role));
        when(platformAdminRoleRepository.isActiveHolder(admin.getId(), role.getId())).thenReturn(true);
        when(platformAdminRoleRepository.countActiveHoldersByRole(role.getId())).thenReturn(2L);

        service.suspend(admin.getId(), UUID.randomUUID());

        assertThat(admin.getStatus()).isEqualTo(PlatformAdminStatus.SUSPENDED);
        verify(tokenRevocationService).revokePlatformAdminTokens(admin.getId());
    }

    @Test
    void revokingPlatformAdminRoleFromTheSoleActiveHolderIsBlocked() {
        UUID adminId = UUID.randomUUID();
        PlatformRole role = platformAdminRole();
        when(platformRoleRepository.findByName("PLATFORM_ADMIN")).thenReturn(Optional.of(role));
        when(platformAdminRoleRepository.isActiveHolder(adminId, role.getId())).thenReturn(true);
        when(platformAdminRoleRepository.countActiveHoldersByRole(role.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.revokeRole(adminId, "PLATFORM_ADMIN", UUID.randomUUID()))
                .isInstanceOf(LastPlatformAdminException.class);
        verify(platformAdminRoleRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokingPlatformAdminRoleFromASuspendedHolderIsAllowed() {
        UUID adminId = UUID.randomUUID();
        PlatformRole role = platformAdminRole();
        when(platformRoleRepository.findByName("PLATFORM_ADMIN")).thenReturn(Optional.of(role));
        // A suspended holder is NOT an active holder → not "the last one", so the revoke proceeds.
        when(platformAdminRoleRepository.isActiveHolder(adminId, role.getId())).thenReturn(false);
        when(platformAdminRoleRepository.findByPlatformAdminIdAndPlatformRoleId(adminId, role.getId()))
                .thenReturn(Optional.empty());

        service.revokeRole(adminId, "PLATFORM_ADMIN", UUID.randomUUID());

        // No last-admin block; the (idempotent) delete path is taken.
        verify(platformAdminRoleRepository).findByPlatformAdminIdAndPlatformRoleId(adminId, role.getId());
    }

    @Test
    void revokingTheLesserSupportRoleIsNotGuarded() {
        UUID adminId = UUID.randomUUID();
        PlatformRole support = new PlatformRole();
        support.setId(UUID.randomUUID());
        support.setName("PLATFORM_SUPPORT");
        when(platformRoleRepository.findByName("PLATFORM_SUPPORT")).thenReturn(Optional.of(support));
        when(platformAdminRoleRepository.findByPlatformAdminIdAndPlatformRoleId(adminId, support.getId()))
                .thenReturn(Optional.empty());

        service.revokeRole(adminId, "PLATFORM_SUPPORT", UUID.randomUUID());

        // The last-admin guard is PLATFORM_ADMIN-specific — it must not even consult the holder count here.
        verify(platformAdminRoleRepository, never()).isActiveHolder(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(platformAdminRoleRepository, never()).countActiveHoldersByRole(org.mockito.ArgumentMatchers.any());
    }

    // ---- FE-3c: self-lockout guard -----------------------------------------

    @Test
    void suspendingYourOwnAccountIsRefusedRegardlessOfOtherActiveAdmins() {
        PlatformAdmin admin = active("boss@x.io", "$2a$real");
        // Deliberately no repository/role stubbing beyond what's needed — the self-check must fire
        // before the last-admin check even looks at holder counts.

        assertThatThrownBy(() -> service.suspend(admin.getId(), admin.getId()))
                .isInstanceOf(SelfActionRefusedException.class)
                .satisfies(ex -> {
                    assertThat(((CloseAuthDomainException) ex).getCategory()).isEqualTo(ErrorCategory.FORBIDDEN);
                    assertThat(((CloseAuthDomainException) ex).getCode()).isEqualTo("platform_admin.self_action_refused");
                });
        verify(platformAdminRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        verify(tokenRevocationService, never()).revokePlatformAdminTokens(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokingYourOwnPlatformAdminRoleIsRefusedRegardlessOfOtherActiveAdmins() {
        UUID adminId = UUID.randomUUID();
        PlatformRole role = platformAdminRole();
        when(platformRoleRepository.findByName("PLATFORM_ADMIN")).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service.revokeRole(adminId, "PLATFORM_ADMIN", adminId))
                .isInstanceOf(SelfActionRefusedException.class);
        verify(platformAdminRoleRepository, never()).delete(org.mockito.ArgumentMatchers.any());
        // The self-check fires before the last-admin holder-count check.
        verify(platformAdminRoleRepository, never()).isActiveHolder(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokingYourOwnLesserSupportRoleIsUnaffectedBySelfGuard() {
        UUID adminId = UUID.randomUUID();
        PlatformRole support = new PlatformRole();
        support.setId(UUID.randomUUID());
        support.setName("PLATFORM_SUPPORT");
        when(platformRoleRepository.findByName("PLATFORM_SUPPORT")).thenReturn(Optional.of(support));
        when(platformAdminRoleRepository.findByPlatformAdminIdAndPlatformRoleId(adminId, support.getId()))
                .thenReturn(Optional.empty());

        // Self-targeting, but PLATFORM_SUPPORT is not the guarded role — nothing gates on it.
        service.revokeRole(adminId, "PLATFORM_SUPPORT", adminId);

        verify(platformAdminRoleRepository).findByPlatformAdminIdAndPlatformRoleId(adminId, support.getId());
    }

    // ---- createPlatformAdmin ----------------------------------------------

    @Test
    void createRejectsDuplicateEmailAsConflict() {
        when(platformAdminRepository.existsByEmail("dup@x.io")).thenReturn(true);

        assertThatThrownBy(() -> service.createPlatformAdmin(
                new CreatePlatformAdminCommand("Dup@X.io", "password1", "A", "B")))
                .isInstanceOf(CloseAuthDomainException.class)
                .satisfies(ex -> assertThat(((CloseAuthDomainException) ex).getCategory())
                        .isEqualTo(ErrorCategory.CONFLICT));

        verify(platformAdminRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createNormalizesEmailHashesPasswordAndSaves() {
        when(platformAdminRepository.existsByEmail("new@x.io")).thenReturn(false);
        when(platformAdminRepository.save(org.mockito.ArgumentMatchers.any(PlatformAdmin.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.createPlatformAdmin(new CreatePlatformAdminCommand(" New@X.io ", "password1", "N", "U"));

        assertThat(view.email()).isEqualTo("new@x.io"); // trimmed + lower-cased
        verify(commandValidator).validate(org.mockito.ArgumentMatchers.any());
        verify(passwordHasher).hash("password1");
    }

    // ---- helpers ----------------------------------------------------------

    private void assertRejected(PlatformAdminAuthResult result, FailureReason expected) {
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(expected);
        assertThat(result.adminId()).isNull();
    }

    private PlatformAdmin active(String email, String hash) {
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(UUID.randomUUID());
        admin.setEmail(email);
        admin.setStatus(PlatformAdminStatus.ACTIVE);
        admin.setPasswordHash(hash);
        admin.setPasswordAlgo("bcrypt");
        return admin;
    }

    private PlatformRole platformAdminRole() {
        PlatformRole role = new PlatformRole();
        role.setId(UUID.randomUUID());
        role.setName("PLATFORM_ADMIN");
        return role;
    }
}

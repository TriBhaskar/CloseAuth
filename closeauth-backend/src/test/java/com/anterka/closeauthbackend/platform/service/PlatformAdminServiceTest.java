package com.anterka.closeauthbackend.platform.service;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
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
                platformRoleRepository, passwordHasher, commandValidator, tokenRevocationService);
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

        service.suspend(admin.getId());

        assertThat(admin.getStatus()).isEqualTo(PlatformAdminStatus.SUSPENDED);
        // A compromised/in-flight token must die instantly, not merely at expiry.
        verify(tokenRevocationService).revokePlatformAdminTokens(admin.getId());
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
}

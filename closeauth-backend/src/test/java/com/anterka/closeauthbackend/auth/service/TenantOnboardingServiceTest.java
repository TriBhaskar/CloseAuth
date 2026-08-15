package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.BootstrapAdminCommand;
import com.anterka.closeauthbackend.auth.dto.TempCredentialReissuedView;
import com.anterka.closeauthbackend.auth.dto.TenantAdminBootstrappedView;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.exception.NoPendingTempCredentialException;
import com.anterka.closeauthbackend.common.exception.TenantAdminAlreadyExistsException;
import com.anterka.closeauthbackend.common.exception.TenantSuspendedException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.notification.service.NotificationDeliveryException;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.service.SystemRoleNames;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantOnboardingService} (Phase 3 issuance). Mirrors {@link PasswordRotationServiceTest}'s
 * style — plain hand-wired Mockito mocks, no extension. Focuses on the decisions §2.9 settled: the
 * already-has-an-admin refusal, the already-rotated refusal on reissue, that the same raw temp password flows
 * into both {@code createUserWithPassword} and {@code issueTempCredential}, and that an email-delivery failure
 * propagates rather than being swallowed (§2.9's deliberate departure from the self-service flows).
 */
class TenantOnboardingServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String tenantSlug = "acme";
    private final String tenantName = "Acme Corp";

    private TenantService tenantService;
    private UserService userService;
    private TenantRoleService tenantRoleService;
    private TenantRoleRepository tenantRoleRepository;
    private PasswordRotationService passwordRotationService;
    private OneTimeTokenGenerator oneTimeTokenGenerator;
    private AuthNotificationSender notifier;
    private CommandValidator commandValidator;
    private CloseAuthProperties properties;
    private AuditEmitter auditEmitter;
    private TenantOnboardingService service;

    @BeforeEach
    void setUp() {
        tenantService = Mockito.mock(TenantService.class);
        userService = Mockito.mock(UserService.class);
        tenantRoleService = Mockito.mock(TenantRoleService.class);
        tenantRoleRepository = Mockito.mock(TenantRoleRepository.class);
        passwordRotationService = Mockito.mock(PasswordRotationService.class);
        oneTimeTokenGenerator = Mockito.mock(OneTimeTokenGenerator.class);
        notifier = Mockito.mock(AuthNotificationSender.class);
        commandValidator = Mockito.mock(CommandValidator.class);
        properties = new CloseAuthProperties();
        auditEmitter = Mockito.mock(AuditEmitter.class);
        service = new TenantOnboardingService(tenantService, userService, tenantRoleService, tenantRoleRepository,
                passwordRotationService, oneTimeTokenGenerator, notifier, commandValidator, properties, auditEmitter);

        when(tenantService.requireActiveTenant(any())).thenReturn(activeTenant());
        when(oneTimeTokenGenerator.generate(eq(OneTimeTokenFormat.OPAQUE_LINK), anyInt())).thenReturn("generated-temp-pw");
        when(userService.createUserWithPassword(any(), any())).thenAnswer(inv -> {
            CreateUserWithPasswordCommand cmd = inv.getArgument(1);
            return userView(cmd.email());
        });
        TenantRole adminRole = new TenantRole();
        adminRole.setId(UUID.randomUUID());
        adminRole.setTenantId(tenantId);
        adminRole.setName(SystemRoleNames.TENANT_ADMIN);
        when(tenantRoleRepository.findByTenantIdAndName(tenantId, SystemRoleNames.TENANT_ADMIN))
                .thenReturn(Optional.of(adminRole));
        when(passwordRotationService.beginRotation(any(), any(), any(), any(), any()))
                .thenReturn("http://localhost:8080/password-rotation?token=raw");
    }

    // ---- bootstrapFirstAdmin -------------------------------------------------

    @Test
    void bootstrapRefusesATenantThatAlreadyHasAnActiveAdmin() {
        when(tenantRoleService.countActiveTenantAdmins(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.bootstrapFirstAdmin(tenantId, command()))
                .isInstanceOf(TenantAdminAlreadyExistsException.class);

        verify(userService, never()).createUserWithPassword(any(), any());
        verify(tenantRoleService, never()).assignTenantRole(any(), any(), any(), any());
        verify(notifier, never()).sendTenantAdminOnboardingLink(any(), any(), any());
    }

    @Test
    void bootstrapPropagatesTheTenantGuardOnANonActiveTenant() {
        when(tenantService.requireActiveTenant(any()))
                .thenThrow(new TenantSuspendedException(tenantId, TenantStatus.PROVISIONING));

        assertThatThrownBy(() -> service.bootstrapFirstAdmin(tenantId, command()))
                .isInstanceOf(TenantSuspendedException.class);

        verify(userService, never()).createUserWithPassword(any(), any());
    }

    @Test
    void bootstrapUsesTheSameGeneratedPasswordForCreationAndIssuanceAndReturnsItExactlyOnce() {
        when(tenantRoleService.countActiveTenantAdmins(any())).thenReturn(0L);

        TenantAdminBootstrappedView result = service.bootstrapFirstAdmin(tenantId, command());

        ArgumentCaptor<CreateUserWithPasswordCommand> createCaptor =
                ArgumentCaptor.forClass(CreateUserWithPasswordCommand.class);
        verify(userService).createUserWithPassword(any(), createCaptor.capture());
        assertThat(createCaptor.getValue().password()).isEqualTo("generated-temp-pw");
        assertThat(createCaptor.getValue().initialStatus()).isEqualTo(UserStatus.ACTIVE);

        ArgumentCaptor<String> issuedPasswordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(userService).issueTempCredential(any(), any(), issuedPasswordCaptor.capture(), expiresAtCaptor.capture());
        assertThat(issuedPasswordCaptor.getValue()).isEqualTo("generated-temp-pw");

        Duration ttl = properties.getOneTimeToken().getTenantAdminOnboardingTtl();
        assertThat(expiresAtCaptor.getValue()).isCloseTo(Instant.now().plus(ttl), within(Duration.ofSeconds(5)));

        assertThat(result.temporaryPassword()).isEqualTo("generated-temp-pw");
        assertThat(result.temporaryPasswordExpiresAt()).isEqualTo(expiresAtCaptor.getValue());
    }

    @Test
    void bootstrapAssignsTenantAdminAndEmitsTempCredentialIssued() {
        when(tenantRoleService.countActiveTenantAdmins(any())).thenReturn(0L);

        service.bootstrapFirstAdmin(tenantId, command());

        verify(tenantRoleService).assignTenantRole(any(), any(), any(), eq(null));
        verify(notifier).sendTenantAdminOnboardingLink(any(), any(), eq(tenantName));
        verify(auditEmitter).emit(argThatIsType(AuditEvents.tempCredentialIssued(tenantId, userId, Instant.now())));
    }

    @Test
    void bootstrapPropagatesAnEmailDeliveryFailureRatherThanSwallowingIt() {
        when(tenantRoleService.countActiveTenantAdmins(any())).thenReturn(0L);
        Mockito.doThrow(new NotificationDeliveryException("TENANT_ADMIN_ONBOARDING", "a@x.com", null))
                .when(notifier).sendTenantAdminOnboardingLink(any(), any(), any());

        assertThatThrownBy(() -> service.bootstrapFirstAdmin(tenantId, command()))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    // ---- reissueOnboardingCredential ------------------------------------------

    @Test
    void reissueRefusesAUserWithNoLocalCredentialState() {
        when(userService.getUserById(any(), eq(userId))).thenReturn(userView("a@x.com"));
        when(userService.getLocalCredentialState(any(), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissueOnboardingCredential(tenantId, userId))
                .isInstanceOf(NoPendingTempCredentialException.class);
        verify(userService, never()).issueTempCredential(any(), any(), any(), any());
    }

    @Test
    void reissueRefusesAUserWhoAlreadyRotated() {
        when(userService.getUserById(any(), eq(userId))).thenReturn(userView("a@x.com"));
        when(userService.getLocalCredentialState(any(), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(false, null)));

        assertThatThrownBy(() -> service.reissueOnboardingCredential(tenantId, userId))
                .isInstanceOf(NoPendingTempCredentialException.class);
        verify(userService, never()).issueTempCredential(any(), any(), any(), any());
        verify(notifier, never()).sendTenantAdminOnboardingLink(any(), any(), any());
    }

    @Test
    void reissueProceedsForAnUnrotatedOrExpiredPendingCredential() {
        when(userService.getUserById(any(), eq(userId))).thenReturn(userView("a@x.com"));
        when(userService.getLocalCredentialState(any(), eq(userId)))
                .thenReturn(Optional.of(new LocalCredentialState(true, Instant.now().minusSeconds(60))));

        TempCredentialReissuedView result = service.reissueOnboardingCredential(tenantId, userId);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.temporaryPassword()).isEqualTo("generated-temp-pw");
        verify(userService).issueTempCredential(any(), eq(userId), eq("generated-temp-pw"), any());
        // Reissue relies on beginRotation's own invalidate-then-issue pairing — proven by PasswordRotationServiceTest;
        // here we only assert it was actually called (the shared primitive, not a second implementation).
        verify(passwordRotationService).beginRotation(any(), eq(userId), eq("a@x.com"), any(), eq(null));
        verify(notifier).sendTenantAdminOnboardingLink(eq("a@x.com"), any(), eq(tenantName));
    }

    // ---- fixtures --------------------------------------------------------------

    private BootstrapAdminCommand command() {
        return new BootstrapAdminCommand("new-admin@x.com", "First", "Last");
    }

    private TenantView activeTenant() {
        return new TenantView(tenantId, tenantSlug, tenantName, TenantStatus.ACTIVE, Instant.now(), Instant.now(),
                null, null);
    }

    private UserView userView(String email) {
        return new UserView(userId, tenantId, email, false, null, false, "First", "Last", UserStatus.ACTIVE,
                null, Instant.now(), Instant.now());
    }

    /** Matches any audit event of the same type as the sample — the payload's exact expiresAt isn't asserted here. */
    private static com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent argThatIsType(
            com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent sample) {
        return org.mockito.ArgumentMatchers.argThat(event -> event != null && event.getEventType() == sample.getEventType());
    }

    private static org.assertj.core.data.TemporalUnitWithinOffset within(Duration duration) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(duration.toMillis(), java.time.temporal.ChronoUnit.MILLIS);
    }
}

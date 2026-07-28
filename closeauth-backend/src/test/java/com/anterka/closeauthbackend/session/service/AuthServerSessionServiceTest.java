package com.anterka.closeauthbackend.session.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.session.dto.CreateSessionCommand;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import com.anterka.closeauthbackend.session.repository.AuthServerSessionRepository;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.repository.TenantRepository;
import com.anterka.closeauthbackend.token.service.RefreshTokenRotationService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit tests for the session mechanism against mocked stores — the security-critical decision logic
 * (tenant-scoping, idle/absolute timeouts, remember-me, the revoke cascade) proven deterministically with a
 * controllable {@link Clock}, same discipline as 4b-i/4b-ii.
 */
class AuthServerSessionServiceTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private SessionHotStore hotStore;
    private AuthServerSessionRepository sessionRepository;
    private RefreshTokenRotationService rotationService;
    private TokenRevocationService tokenRevocationService;
    private TenantRepository tenantRepository;
    private CloseAuthProperties properties;
    private MutableClock clock;
    private AuthServerSessionService service;

    @BeforeEach
    void setUp() {
        hotStore = Mockito.mock(SessionHotStore.class);
        sessionRepository = Mockito.mock(AuthServerSessionRepository.class);
        rotationService = Mockito.mock(RefreshTokenRotationService.class);
        tokenRevocationService = Mockito.mock(TokenRevocationService.class);
        tenantRepository = Mockito.mock(TenantRepository.class);
        // Default: the session's tenant is ACTIVE, so happy-path validation reaches its intended result.
        when(tenantRepository.findStatusById(any())).thenReturn(Optional.of(TenantStatus.ACTIVE));
        properties = new CloseAuthProperties(); // idle 1h, absolute 12h, remember 30d, allowed=true
        clock = new MutableClock(Instant.parse("2026-07-04T10:00:00Z"));
        service = new AuthServerSessionService(hotStore, sessionRepository, rotationService,
                tokenRevocationService, tenantRepository, properties, clock,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        // save() echoes the row back with a generated id (JPA would).
        when(sessionRepository.save(any(AuthServerSession.class))).thenAnswer(inv -> {
            AuthServerSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });
    }

    // ---- create -----------------------------------------------------------

    @Test
    void createPersistsLedgerAndHotStoreWithCorrectWindows() {
        Instant now = clock.instant();
        SessionView view = service.createSession(new CreateSessionCommand(userId, tenantA, "1.2.3.4", "UA", false, "pwd"));

        ArgumentCaptor<SessionHotState> stateCaptor = ArgumentCaptor.forClass(SessionHotState.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(hotStore).save(stateCaptor.capture(), ttlCaptor.capture());
        SessionHotState state = stateCaptor.getValue();

        assertThat(view.sessionKey()).isNotBlank();
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.tenantId()).isEqualTo(tenantA);
        assertThat(state.tenantId()).isEqualTo(tenantA.toString());
        assertThat(state.idleExpiresAtInstant()).isEqualTo(now.plus(Duration.ofHours(1)));    // idle 1h
        assertThat(state.absoluteExpiresAtInstant()).isEqualTo(now.plus(Duration.ofHours(12))); // absolute 12h
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(1)); // TTL = earliest expiry = idle
    }

    @Test
    void rememberMeExtendsAbsoluteButIdleStillApplies() {
        Instant now = clock.instant();
        service.createSession(new CreateSessionCommand(userId, tenantA, null, null, true, "pwd"));

        ArgumentCaptor<SessionHotState> stateCaptor = ArgumentCaptor.forClass(SessionHotState.class);
        verify(hotStore).save(stateCaptor.capture(), any(Duration.class));
        SessionHotState state = stateCaptor.getValue();

        assertThat(state.rememberMe()).isTrue();
        assertThat(state.absoluteExpiresAtInstant()).isEqualTo(now.plus(Duration.ofDays(30))); // remember-me absolute
        assertThat(state.idleExpiresAtInstant()).isEqualTo(now.plus(Duration.ofHours(1)));      // idle unchanged
    }

    @Test
    void rememberMeIgnoredWhenPolicyDisallowsIt() {
        Instant now = clock.instant();
        properties.getSession().setRememberMeAllowed(false);
        service.createSession(new CreateSessionCommand(userId, tenantA, null, null, true, "pwd"));

        ArgumentCaptor<SessionHotState> stateCaptor = ArgumentCaptor.forClass(SessionHotState.class);
        verify(hotStore).save(stateCaptor.capture(), any(Duration.class));
        assertThat(stateCaptor.getValue().rememberMe()).isFalse();
        assertThat(stateCaptor.getValue().absoluteExpiresAtInstant()).isEqualTo(now.plus(Duration.ofHours(12)));
    }

    // ---- validate: happy + slide ------------------------------------------

    @Test
    void validateReturnsSessionAndSlidesIdleWindow() {
        String key = "sk";
        when(hotStore.find(key)).thenReturn(Optional.of(state(tenantA, plus(Duration.ofMinutes(30)), plus(Duration.ofHours(6)))));

        Optional<SessionView> result = service.validateSession(key, tenantA);

        assertThat(result).isPresent();
        ArgumentCaptor<SessionHotState> slid = ArgumentCaptor.forClass(SessionHotState.class);
        verify(hotStore).save(slid.capture(), any(Duration.class));
        assertThat(slid.getValue().idleExpiresAtInstant()).isEqualTo(plus(Duration.ofHours(1))); // slid to now+idle
        verify(sessionRepository).touchOnValidation(eq(key), any(Instant.class), any(Instant.class));
    }

    // ---- validate: TENANT SCOPING (the critical property) -----------------

    @Test
    void sessionNeverValidatesAgainstADifferentTenant() {
        String key = "sk";
        when(hotStore.find(key)).thenReturn(Optional.of(state(tenantA, plus(Duration.ofMinutes(30)), plus(Duration.ofHours(6)))));

        // Created in tenant A, presented in a tenant-B authorization context → "no session".
        assertThat(service.validateSession(key, tenantB)).isEmpty();
        verify(hotStore, never()).save(any(), any());                      // no slide
        verify(sessionRepository, never()).touchOnValidation(any(), any(), any()); // no ledger touch, no leak
    }

    @Test
    void multiTenantSameBrowserEachValidatesOnlyAgainstItsOwnTenant() {
        when(hotStore.find("kA")).thenReturn(Optional.of(state("kA", tenantA, plus(Duration.ofMinutes(30)), plus(Duration.ofHours(6)))));
        when(hotStore.find("kB")).thenReturn(Optional.of(state("kB", tenantB, plus(Duration.ofMinutes(30)), plus(Duration.ofHours(6)))));

        assertThat(service.validateSession("kA", tenantA)).isPresent();
        assertThat(service.validateSession("kA", tenantB)).isEmpty();
        assertThat(service.validateSession("kB", tenantB)).isPresent();
        assertThat(service.validateSession("kB", tenantA)).isEmpty();
    }

    // ---- validate: timeouts -----------------------------------------------

    @Test
    void idleExpiredSessionIsInvalidAndPurged() {
        String key = "sk";
        when(hotStore.find(key)).thenReturn(Optional.of(state(tenantA, plus(Duration.ofMinutes(-1)), plus(Duration.ofHours(6)))));

        assertThat(service.validateSession(key, tenantA)).isEmpty();
        verify(hotStore).delete(key);
    }

    @Test
    void absoluteExpiredSessionIsInvalidEvenIfIdleIsFresh() {
        String key = "sk";
        when(hotStore.find(key)).thenReturn(Optional.of(state(tenantA, plus(Duration.ofMinutes(30)), plus(Duration.ofMinutes(-1)))));

        assertThat(service.validateSession(key, tenantA)).isEmpty();
        verify(hotStore).delete(key);
    }

    @Test
    void missingHotEntryIsNoSession() {
        when(hotStore.find("gone")).thenReturn(Optional.empty());
        assertThat(service.validateSession("gone", tenantA)).isEmpty();
    }

    // ---- validate: TENANT-STATUS gate (IT-9 fix, 1b — closes the SSO bypass) ----

    @Test
    void sessionForANonActiveTenantIsNoSession() {
        String key = "sk";
        when(hotStore.find(key)).thenReturn(Optional.of(state(tenantA, plus(Duration.ofMinutes(30)), plus(Duration.ofHours(6)))));
        // The session itself is live and tenant-matched, but its tenant has been suspended.
        when(tenantRepository.findStatusById(tenantA)).thenReturn(Optional.of(TenantStatus.SUSPENDED));

        assertThat(service.validateSession(key, tenantA))
                .as("a suspended tenant's session must not validate (no SSO bypass)").isEmpty();
        verify(hotStore, never()).save(any(), any());                              // no slide
        verify(sessionRepository, never()).touchOnValidation(any(), any(), any()); // no ledger touch
    }

    @Test
    void sessionForADeletedTenantIsNoSession() {
        String key = "sk";
        when(hotStore.find(key)).thenReturn(Optional.of(state(tenantA, plus(Duration.ofMinutes(30)), plus(Duration.ofHours(6)))));
        when(tenantRepository.findStatusById(tenantA)).thenReturn(Optional.of(TenantStatus.DELETED));

        assertThat(service.validateSession(key, tenantA)).isEmpty();
    }

    // ---- revoke cascade (all four legs) -----------------------------------

    @Test
    void revokeSessionCascadesToRedisLedgerRefreshFamiliesAndAccessMarker() {
        String key = "sk";
        UUID sessionId = UUID.randomUUID();
        AuthServerSession row = ledgerRow(sessionId, tenantA, userId);
        when(sessionRepository.findBySessionKey(key)).thenReturn(Optional.of(row));
        when(rotationService.revokeSessionFamilies(sessionId)).thenReturn(2);

        service.revokeSession(key);

        verify(hotStore).delete(key);                                              // leg 1: Redis hot entry
        assertThat(row.getRevokedAt()).isNotNull();                                 // leg 2: ledger row
        verify(sessionRepository).save(row);
        verify(rotationService).revokeSessionFamilies(sessionId);                   // leg 3: refresh families (4b-i)
        verify(tokenRevocationService).revokeAllUserTokens(tenantA, userId);        // leg 4: access marker (4b-ii)
    }

    @Test
    void revokeAllUserSessionsKillsEachSessionAndCascadesUserWide() {
        AuthServerSession s1 = ledgerRow(UUID.randomUUID(), tenantA, userId);
        s1.setSessionKey("k1");
        AuthServerSession s2 = ledgerRow(UUID.randomUUID(), tenantA, userId);
        s2.setSessionKey("k2");
        when(sessionRepository.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantA)).thenReturn(List.of(s1, s2));

        int killed = service.revokeAllUserSessions(tenantA, userId);

        assertThat(killed).isEqualTo(2);
        verify(hotStore).delete("k1");
        verify(hotStore).delete("k2");
        assertThat(s1.getRevokedAt()).isNotNull();
        assertThat(s2.getRevokedAt()).isNotNull();
        verify(rotationService).revokeAllUserFamilies(userId, tenantA);
        verify(tokenRevocationService).revokeAllUserTokens(tenantA, userId);
    }

    // ---- listing ----------------------------------------------------------

    @Test
    void listExcludesAbsoluteExpiredSessions() {
        AuthServerSession live = ledgerRow(UUID.randomUUID(), tenantA, userId);
        live.setAbsoluteExpiresAt(plus(Duration.ofHours(6)));
        AuthServerSession expired = ledgerRow(UUID.randomUUID(), tenantA, userId);
        expired.setAbsoluteExpiresAt(plus(Duration.ofMinutes(-1)));
        when(sessionRepository.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantA))
                .thenReturn(List.of(live, expired));

        List<SessionView> views = service.listSessionsForUser(tenantA, userId);

        assertThat(views).extracting(SessionView::id).containsExactly(live.getId());
    }

    // ---- helpers ----------------------------------------------------------

    private Instant plus(Duration d) {
        return clock.instant().plus(d);
    }

    private SessionHotState state(UUID tenantId, Instant idleExpiresAt, Instant absoluteExpiresAt) {
        return state("sk", tenantId, idleExpiresAt, absoluteExpiresAt);
    }

    private SessionHotState state(String key, UUID tenantId, Instant idleExpiresAt, Instant absoluteExpiresAt) {
        return new SessionHotState(UUID.randomUUID().toString(), key, userId.toString(), tenantId.toString(),
                false, clock.instant().toEpochMilli(), idleExpiresAt.toEpochMilli(), absoluteExpiresAt.toEpochMilli(), "pwd");
    }

    private AuthServerSession ledgerRow(UUID id, UUID tenantId, UUID userId) {
        AuthServerSession s = new AuthServerSession();
        s.setId(id);
        s.setUserId(userId);
        s.setTenantId(tenantId);
        s.setSessionKey("sk");
        s.setCreatedAt(clock.instant());
        s.setLastAccessedAt(clock.instant());
        s.setIdleExpiresAt(plus(Duration.ofHours(1)));
        s.setAbsoluteExpiresAt(plus(Duration.ofHours(12)));
        return s;
    }

    /** A hand-advanceable clock so idle/absolute timeout crossings are deterministic. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

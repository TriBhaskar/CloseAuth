package com.anterka.closeauthbackend.session.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.session.dto.CreateSessionCommand;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import com.anterka.closeauthbackend.session.repository.AuthServerSessionRepository;
import com.anterka.closeauthbackend.token.service.RefreshTokenRotationService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Auth Server session mechanism (§7.5) — the primitive that makes SSO real. Stage 5 owns the session as a
 * <b>thing</b> (create / validate / tenant-scope / timeout / revoke / list) as callable operations; the flows that
 * use it (login establishes, {@code /authorize} consults, logout kills) are Stage 6.
 *
 * <h2>Storage split</h2>
 * <ul>
 *   <li><b>{@link SessionHotStore} (Redis)</b> — authoritative for the hot-path {@code validateSession} decision
 *       (tenant + timeouts). Fast, shared across instances.</li>
 *   <li><b>{@code auth_server_sessions} ledger (Postgres)</b> — durable record for cross-device listing and audit
 *       joins; outlives Redis TTLs. NOT consulted for the validity <em>decision</em>.</li>
 * </ul>
 *
 * <h2>Tenant-scoping — the security heart</h2>
 * A session is bound to exactly one tenant at creation. {@link #validateSession} returns a session ONLY if the
 * session's {@code tenant_id} equals the tenant of the current authorization request (passed by the caller, resolved
 * from {@code client_id} per 4a). A session established in tenant A, presented in a tenant-B context, validates as
 * "no session" — forcing fresh authentication in B. A single browser therefore holds multiple tenant sessions without
 * cross-contamination: the same opaque cookie can't grant cross-tenant access because validation is always qualified
 * by the request's tenant.
 *
 * <h2>Revoke cascade (full instant-kill)</h2>
 * {@link #revokeSession} kills a session on every token type: (1) delete the Redis hot entry, (2) mark the ledger row
 * revoked, (3) revoke the session's refresh-token families (4b-i, session-scoped), (4) write the access-token
 * revocation marker (4b-ii). A revoked session cannot continue on any token.
 */
@Service
@Slf4j
public class AuthServerSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SESSION_KEY_BYTES = 32; // 256 bits of entropy in the opaque cookie value

    private final SessionHotStore hotStore;
    private final AuthServerSessionRepository sessionRepository;
    private final RefreshTokenRotationService refreshTokenRotationService;
    private final TokenRevocationService tokenRevocationService;
    private final CloseAuthProperties properties;
    private final Clock clock;

    public AuthServerSessionService(SessionHotStore hotStore,
                                    AuthServerSessionRepository sessionRepository,
                                    RefreshTokenRotationService refreshTokenRotationService,
                                    TokenRevocationService tokenRevocationService,
                                    CloseAuthProperties properties,
                                    Clock clock) {
        this.hotStore = hotStore;
        this.sessionRepository = sessionRepository;
        this.refreshTokenRotationService = refreshTokenRotationService;
        this.tokenRevocationService = tokenRevocationService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Establishes a new tenant-scoped Auth Server session for an authenticated user: writes the durable ledger row
     * and the Redis hot entry, and returns the view (including the opaque {@code session_key} the caller puts in the
     * session cookie).
     */
    @Transactional
    public SessionView createSession(CreateSessionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        UUID userId = Objects.requireNonNull(command.userId(), "userId must not be null");
        UUID tenantId = Objects.requireNonNull(command.tenantId(), "tenantId must not be null");

        CloseAuthProperties.Session cfg = properties.getSession();
        Instant now = Instant.now(clock);
        boolean rememberMe = command.rememberMe() && cfg.isRememberMeAllowed();
        Duration absoluteWindow = rememberMe ? cfg.getRememberMeTimeout() : cfg.getAbsoluteTimeout();
        Instant idleExpiresAt = now.plus(cfg.getIdleTimeout());
        Instant absoluteExpiresAt = now.plus(absoluteWindow);
        String sessionKey = newSessionKey();

        AuthServerSession row = new AuthServerSession();
        row.setUserId(userId);
        row.setTenantId(tenantId);
        row.setSessionKey(sessionKey);
        row.setIpAddress(command.ipAddress());
        row.setUserAgent(command.userAgent());
        row.setRememberMe(rememberMe);
        row.setIdleExpiresAt(idleExpiresAt);
        row.setAbsoluteExpiresAt(absoluteExpiresAt);
        row.setCreatedAt(now);
        row.setLastAccessedAt(now);
        AuthServerSession saved = sessionRepository.save(row);

        SessionHotState state = new SessionHotState(saved.getId().toString(), sessionKey, userId.toString(),
                tenantId.toString(), rememberMe, now.toEpochMilli(), idleExpiresAt.toEpochMilli(),
                absoluteExpiresAt.toEpochMilli(), command.amr());
        hotStore.save(state, hotTtl(now, idleExpiresAt, absoluteExpiresAt));

        log.info("Auth Server session created id={} user={} tenant={} rememberMe={}",
                saved.getId(), userId, tenantId, rememberMe);
        // TODO(stage-8): emit a SESSION_CREATED audit event via the audit outbox (§7.11).
        return SessionView.from(saved);
    }

    /**
     * Tenant-scoped validation (the SSO recognition primitive). Returns the session iff it exists in the hot store,
     * is within BOTH the idle and absolute timeouts, AND belongs to {@code tenantId}. On success, slides the idle
     * window (in Redis) and updates {@code last_accessed_at} (best-effort ledger bookkeeping). Deliberately NOT
     * {@code @Transactional}: the validity decision is Redis-only; the ledger touch is self-transactional and its
     * failure never affects the decision.
     */
    public Optional<SessionView> validateSession(String sessionKey, UUID tenantId) {
        if (sessionKey == null || tenantId == null) {
            return Optional.empty();
        }
        Optional<SessionHotState> maybe = hotStore.find(sessionKey);
        if (maybe.isEmpty()) {
            return Optional.empty(); // absent / idle-evicted / Redis unreadable → no session
        }
        SessionHotState state = maybe.get();
        Instant now = Instant.now(clock);

        if (isExpired(now, state)) {
            hotStore.delete(sessionKey); // idle- or absolute-expired: purge the dead hot entry
            return Optional.empty();
        }

        // ---- TENANT SCOPING (the security invariant): never validate a session against a different tenant. ----
        if (!state.tenantId().equals(tenantId.toString())) {
            // "No session" for tenant B — forces fresh authentication there. No slide, no leak, no ledger touch.
            return Optional.empty();
        }

        // Slide the idle window, capped at the (immovable) absolute expiry.
        Instant slidIdle = earliest(now.plus(properties.getSession().getIdleTimeout()), state.absoluteExpiresAtInstant());
        SessionHotState slid = state.withIdleExpiresAt(slidIdle.toEpochMilli());
        hotStore.save(slid, hotTtl(now, slidIdle, state.absoluteExpiresAtInstant()));
        touchLedger(sessionKey, now, slidIdle);

        return Optional.of(SessionView.fromHotState(slid, now));
    }

    /**
     * Full instant-kill for a single session (logout / admin action / replay fallout). Cascades to: Redis hot entry,
     * ledger row, this session's refresh-token families (4b-i), and the user's access-token revocation marker (4b-ii).
     */
    @Transactional
    public void revokeSession(String sessionKey) {
        Objects.requireNonNull(sessionKey, "sessionKey must not be null");
        hotStore.delete(sessionKey);

        AuthServerSession row = sessionRepository.findBySessionKey(sessionKey).orElse(null);
        if (row == null) {
            log.info("revokeSession: no ledger row for the presented session key (already gone); hot entry deleted");
            return;
        }
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(Instant.now(clock));
            sessionRepository.save(row);
        }

        // Leg 3 (4b-i): session-scoped refresh-family revocation — only THIS session's refresh tokens.
        int refreshFamiliesRevoked = refreshTokenRotationService.revokeSessionFamilies(row.getId());
        // Leg 4 (4b-ii): access-token marker. Necessarily user-wide (access-token JWTs carry no session claim), so it
        // transiently revokes the user's OTHER live sessions' access tokens too; that self-heals within the access-TTL
        // as those sessions refresh (their refresh families were NOT revoked). See the report's revoke-cascade note.
        tokenRevocationService.revokeAllUserTokens(row.getTenantId(), row.getUserId());

        log.info("Auth Server session revoked id={} user={} tenant={} refreshFamiliesRevoked={}",
                row.getId(), row.getUserId(), row.getTenantId(), refreshFamiliesRevoked);
        // TODO(stage-8): emit a SESSION_REVOKED audit event via the audit outbox (§7.11).
    }

    /**
     * Kills ALL of a user's active sessions in a tenant (e.g. "log out everywhere", or a suspension). Cascades to all
     * of the user's refresh families (4b-i) and the access-token marker (4b-ii). Returns the number of sessions killed.
     */
    @Transactional
    public int revokeAllUserSessions(UUID tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        List<AuthServerSession> active = sessionRepository.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantId);
        Instant now = Instant.now(clock);
        for (AuthServerSession row : active) {
            hotStore.delete(row.getSessionKey());
            row.setRevokedAt(now);
        }
        sessionRepository.saveAll(active);

        refreshTokenRotationService.revokeAllUserFamilies(userId, tenantId); // all families for the user
        tokenRevocationService.revokeAllUserTokens(tenantId, userId);         // access-token marker

        log.info("Revoked all {} active session(s) for user={} tenant={}", active.size(), userId, tenantId);
        // TODO(stage-8): emit SESSION_REVOKED audit events via the audit outbox (§7.11).
        return active.size();
    }

    /**
     * Lists a user's active sessions across devices (the data behind the future {@code /v1/me/sessions} endpoint —
     * the endpoint is Stage 7). Reads the ledger (authoritative for listing, outlives Redis TTLs); excludes revoked
     * and absolute-expired rows.
     */
    @Transactional(readOnly = true)
    public List<SessionView> listSessionsForUser(UUID tenantId, UUID userId) {
        Instant now = Instant.now(clock);
        return sessionRepository.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantId).stream()
                .filter(s -> s.getAbsoluteExpiresAt().isAfter(now))
                .map(SessionView::from)
                .toList();
    }

    /**
     * Stage 5 linkage seam: associate a refresh-token family with a session so {@link #revokeSession} cascades to it.
     * The wiring of "issue refresh token → link to current session" is Stage 6 (the auth-code flow).
     */
    @Transactional
    public void linkRefreshFamilyToSession(UUID familyId, UUID sessionId) {
        refreshTokenRotationService.linkFamilyToSession(familyId, sessionId);
    }

    /**
     * Looks up a session by its ledger id (durable record, incl. ip/user-agent). Used by the Stage 6a auth-code flow
     * to source the request context captured at login onto the refresh-token ledger. Not a hot-path validation.
     */
    @Transactional(readOnly = true)
    public Optional<SessionView> getById(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(SessionView::from);
    }

    // ---- decision helpers (pure; unit-tested directly) ---------------------------------------------------------

    /** A session is expired if {@code now} has reached EITHER the idle expiry or the absolute cap (safe direction). */
    static boolean isExpired(Instant now, SessionHotState state) {
        return !now.isBefore(state.idleExpiresAtInstant()) || !now.isBefore(state.absoluteExpiresAtInstant());
    }

    /** Redis TTL = time until the earliest of idle/absolute expiry (never negative). */
    private static Duration hotTtl(Instant now, Instant idleExpiresAt, Instant absoluteExpiresAt) {
        Duration ttl = Duration.between(now, earliest(idleExpiresAt, absoluteExpiresAt));
        return ttl.isNegative() ? Duration.ZERO : ttl;
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private void touchLedger(String sessionKey, Instant now, Instant slidIdle) {
        try {
            sessionRepository.touchOnValidation(sessionKey, now, slidIdle);
        } catch (RuntimeException ledgerDown) {
            // Best-effort bookkeeping — Redis remains authoritative for validity, so a failed ledger touch is benign.
            log.warn("Failed to update session ledger last_accessed_at for '{}' (Redis remains authoritative).",
                    sessionKey, ledgerDown);
        }
    }

    private static String newSessionKey() {
        byte[] bytes = new byte[SESSION_KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

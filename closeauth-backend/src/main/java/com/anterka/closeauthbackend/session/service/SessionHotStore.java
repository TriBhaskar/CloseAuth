package com.anterka.closeauthbackend.session.service;

import java.time.Duration;
import java.util.Optional;

/**
 * Port for the session hot store (§7.5) — the fast, shared (Redis) store that is authoritative for the
 * {@code validateSession} decision. Abstracted behind an interface so the session lifecycle logic
 * ({@code AuthServerSessionService}) is unit-testable without Redis (same discipline as 4b-ii's
 * {@code RevocationMarkerStore}).
 *
 * <p><b>Failure semantics (documented in the Redis impl):</b> a read failure returns {@link Optional#empty()} — i.e.
 * "no session", which safely forces fresh authentication (fail toward re-auth). This is the opposite lean to the
 * 4b-ii revocation list (which fails <em>open</em>): here the hot store <em>is</em> the session's source of truth, so
 * its unavailability must not silently admit a session it can no longer verify.
 */
public interface SessionHotStore {

    /** Writes (or overwrites) the session state with the given TTL (aligned to the earliest of idle/absolute expiry). */
    void save(SessionHotState state, Duration ttl);

    /** Reads the session state, or empty if absent/evicted/unreadable. */
    Optional<SessionHotState> find(String sessionKey);

    /** Removes the session from the hot store (revocation / logout / expiry cleanup). */
    void delete(String sessionKey);
}

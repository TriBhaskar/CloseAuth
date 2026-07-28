# closeauth-integration-tests

Black-box integration tests for the CloseAuth backend. They boot the **real** backend image (built from
`closeauth-backend/Dockerfile`) networked with real **Postgres**, **Redis**, and **Mailpit** via
[Testcontainers](https://testcontainers.com/), then drive the app over HTTP exactly as a client would and verify both
the API responses **and** the actual side effects (database rows, captured emails).

> **This module has ZERO dependency on `closeauth-backend`'s Java classpath.** It knows the backend only as an HTTP
> service and a Postgres schema it can read. It does not import backend classes, entities, or DTOs. That separation is
> the point — do not add `closeauth-backend` as a Maven dependency.

## Prerequisites

- **Docker** running (Testcontainers needs it). If Docker is absent, the suite **skips** (it does not fail).
- **Java 21** and **Maven** (same Java as the backend).
- The **backend boot jar must be built first**, because `closeauth-backend/Dockerfile` is jar-based
  (`COPY target/*.jar`). If the jar is missing the suite skips with a message telling you to build it.

## How to run

From the **repository root**:

```bash
# One shot: package the backend (produces the jar) and run the integration suite.
mvn -pl closeauth-backend,closeauth-integration-tests package
```

or, in two steps:

```bash
mvn -pl closeauth-backend -am package -DskipTests    # build the backend boot jar
mvn -pl closeauth-integration-tests test             # run the integration suite (uses the jar to build the app image)
```

First run pulls images (`postgres:17-alpine`, `redis:8-alpine`, `axllent/mailpit`) and builds the backend image, so it
is slower; later runs reuse the Docker layer cache. No other manual setup is required beyond Docker.

> **Docker API compatibility:** Docker Engine 29+ dropped support for API versions below 1.40, and the docker-java
> client bundled with Testcontainers otherwise defaults to 1.32 (every call then 400s with *"client version 1.32 is
> too old"*). `src/test/resources/docker-java.properties` pins `api.version=1.44` (read from the classpath by
> docker-java) to fix this with no manual setup. If a future daemon raises its minimum API above 1.44, bump that value.

Nothing else needs to be started by hand — the app container runs Flyway migrations itself on boot (as in normal
operation), so there is no separate migration step.

## What the harness wires up

`support/CloseAuthStack` is the reusable orchestration (a JVM-wide singleton — started once, shared by every test):

| Container | Image | In-network alias | Notes |
|---|---|---|---|
| Postgres | `postgres:17-alpine` | `postgres` | matches `docker-compose.yml`; the app migrates it via Flyway on boot |
| Redis | `redis:8-alpine` | `redis` | matches `docker-compose.yml` |
| Mailpit | `axllent/mailpit` | `mailpit` | SMTP `1025` + REST API `8025`; captures the app's outbound email |
| Backend | built from `closeauth-backend/Dockerfile` | `closeauth-backend` | `docker` profile, port `9000`, context path `/closeauth` |

The app container is configured to reach the others by their **network aliases** (not `localhost`):
`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/closeauth`, `SPRING_DATA_REDIS_HOST=redis`, `SMTP_HOST=mailpit`
`SMTP_PORT=1025`, with `SMTP_AUTH=false` / `SMTP_STARTTLS=false` (Mailpit rejects auth/STARTTLS by default), and a
bootstrap platform-admin credential injected via `JAVA_OPTS` (`-Dcloseauth.platform-admin.bootstrap-*`). Readiness is an
HTTP wait on `GET /closeauth/actuator/health`.

## Conventions every future test class MUST follow

- **Extend `support.IntegrationTest`.** It boots the stack, points REST Assured at the app (`baseURI` + `/closeauth`
  context path), skips gracefully when Docker/jar are absent, and gives you `mailpit()`, `db()`, and
  `platformAdminToken()`.
- **Isolate via unique fixture data, not a clean database.** Containers are shared across test methods (booting is
  slow), so **never assume an empty DB between methods**. Create a fresh tenant / user / client per test using
  `support.Fixtures` (`slug(...)`, `email(...)`, `clientId(...)` — each carries a random suffix). Because every `mvn`
  run gets fresh containers, a second run is never polluted by the first.
- **Wait for emails, don't sleep.** Use `mailpit().waitForMessageTo(email, timeout)` — a bounded poll — then extract
  codes/links from the message body. No backdoor access to secrets: the code comes out of the real email.
- **Wait for async side effects, don't sleep.** The audit trail is written through an outbox drained every ~2s, so an
  event isn't queryable the instant its action completes. Poll the query endpoint until the event appears (see
  `AuditQueryJourneyTest.awaitPresent`) — the same bounded-poll discipline as the Mailpit flows — rather than asserting
  immediately or sleeping a fixed duration.
- **Assert side effects, not just status codes.** Use `db().queryOne("select ... where ...", args)` for a direct,
  read-only JDBC check that the DB actually changed. Keep it read-only — this module never mutates the database.
- **Name tests as journeys.** Class `*JourneyTest`, method reads as a user story
  (e.g. `registersVerifiesAndLogsIn`), under `com.anterka.closeauth.it.journeys`.

## The OAuth2 flow client (`support/OAuthFlowClient`) — read this before writing a flow-driven journey

`OAuthFlowClient` drives the **real Authorization Code + PKCE flow** against the running app, exactly as a browser
client would. Nearly every future journey (consent, token lifecycle, RBAC-gated actions, session revocation) needs to
get from "no session" to "have valid tokens" — use this, don't hand-roll it. Obtain it via `oauthFlow()` on the base
class.

**What it handles for you**

- **PKCE (S256, RFC 7636).** Every `/authorize` carries a `code_challenge`; every token exchange the matching
  `code_verifier`. The fixture clients are registered `requireProofKey=true`.
- **A browser-like cookie jar** carried across the whole flow: the servlet `SESSION` cookie (holds SAS's saved
  `/authorize` request) and the `CLOSEAUTH_SESSION` Auth Server session cookie.
- **Manual redirect inspection** — redirects are never auto-followed; you observe the `Location` to tell
  "SSO recognized → 302 to the client callback with a `code`" from "not recognized → 302 to `/login`".

**Key methods**

| Method | Use |
|---|---|
| `login(clientId, clientSecret, email, password)` | Full happy path from scratch → `LoginResult(tokens, session)`. |
| `authorize(clientId, session, scope)` | Consent-aware `/authorize` (**without** logging in): classifies into `AuthorizeOutcome` with an `Outcome` of `CODE_ISSUED` / `LOGIN_REQUIRED` / `CONSENT_REQUIRED`. For `CONSENT_REQUIRED` it captures the consent-redirect `scope`/`state` and the updated cookie jar (`session()`) to thread into the consent calls. |
| `authorizeOnly(clientId, session)` | Back-compat thin wrapper: `authorize(...)` for the default `openid` scope; use `ssoRecognized()` as before. |
| `fetchConsentContext(clientId, session, scope, state)` | `GET /oauth2/consent` → parsed `ConsentContext(clientId, clientName, state, scopes:[ConsentScope(scope, description, requiresConsent)], alreadyGranted)`; `ctx.scope(name)` finds one entry. |
| `submitConsent(clientId, session, state, approvedScopes)` | `POST /oauth2/authorize` approving `approvedScopes` (one `scope` form param each; **empty list = deny**) → `AuthorizeOutcome` (`CODE_ISSUED` with a code, or `ACCESS_DENIED`). Exchange the code with the **triggering** `authorize`'s `codeVerifier` (that's where the PKCE challenge was bound). |
| `exchange(clientId, clientSecret, code, codeVerifier)` | Code → `TokenResponse`. |
| `refresh(clientId, clientSecret, refreshToken)` | Raw `grant_type=refresh_token` response (IT-9) — for probing whether a pre-existing refresh token still works after a lifecycle change (e.g. tenant suspension). |
| `revoke(clientId, clientSecret, token, tokenTypeHint)` | Raw `POST /oauth2/revoke` response (RFC 7009, IT-11) — asserts revocation success incl. the "already-invalid token still 200" no-oracle quirk. |
| `logout(clientId, session)` · `isActive(clientId, clientSecret, token)` | RP-initiated logout · RFC 7662 introspection (`active`). |
| `requestMagicLink(email, clientId)` · `consumeMagicLink(token, clientId)` | Magic-link email request (enumeration-safe 200) · consume → `SessionState` (a magic-link session, usable for SSO exactly like a password one; `amr=magic_link`, `idp` unchanged). |
| `requestPasswordReset(email, clientId)` · `confirmPasswordReset(token, newPassword, clientId)` | Reset email request (enumeration-safe 200) · confirm with the emailed token. |
| `attemptPasswordLogin(clientId, email, password)` | Raw `POST /login` response (302 success / 401 bad creds) — for asserting a login *fails* (e.g. old password after reset). |
| `register(email, password, clientId[, inviteToken])` | Raw `POST /register` response — drives self-registration in any mode (200 `{userId,status,mode,emailVerificationSent}`, 409 duplicate, 403 invite-only refusal). `inviteToken` is sent only for `INVITE_ONLY`. |

**Extracting a token from an email link.** The verification code (IT-1) is a 6-digit number; the magic-link and
password-reset secrets arrive as a `token` URL query-param inside a link in the email body (`Emails.linkToken(body)`),
while an **invite** link uses its own `invite` param (`Emails.linkParam(body, "invite")` — different flows, different
param names). Note the link's host/port is the app's configured issuer/BFF base and does **not** match the container's
mapped port — so use the extracted *token* with the flow-client methods (which target the mapped port), never the link
URL verbatim.

**Cookie-state swapping (the important part).** `LoginResult.session()` is an immutable `SessionState` snapshot of the
jar. `authorizeOnly(...)`, `logout(...)` **read** a `SessionState` without mutating it — so the *same* post-login
session can be replayed against a different client (e.g. proving a Tenant-A session is refused by a Tenant-B client) or
after logout (proving it's dead) without re-logging-in. `SessionState.sessionKey()` returns the `CLOSEAUTH_SESSION`
value, which is also the `auth_server_sessions.session_key` for DB assertions.

**Confidential clients.** Flow fixtures use a client secret (Basic auth at `/token` + `/introspect`) because SAS does
not issue refresh tokens to public clients — needed to assert refresh-family revocation. PKCE still applies.

**Public (unauthenticated) endpoints.** A few endpoints render before login and take no bearer token (e.g. the branding
resolution `GET /branding?client_id=...`, IT-10). There's no client helper for these — call them with bare REST Assured
(`RestAssured.given().queryParam(...).get("/branding")`), which sends **no** `Authorization` header (the base class has
already pointed `baseURI`/`basePath` at the app). That "no auth header at all" is itself the property under test.

**Protocol endpoints + real signature verification (IT-11).** The SAS-provided protocol surface (`/oauth2/jwks`,
`/.well-known/openid-configuration`, `/userinfo`, `/oauth2/revoke`, `/connect/logout`) is driven with bare REST Assured
too. IT-11 added the module's **only JWT-crypto dependency**, `com.nimbusds:nimbus-jose-jwt` (test scope) — the same
well-established library the backend uses — to **cryptographically verify** a real token's RS256 signature against the
published JWKS (`JWKSet.parse` → match by `kid` → `RSASSAVerifier`), with a tampered-signature negative control. Prior
stages only *decoded* claims (`support/Jwt`, no signature check); this closes that standing trust assumption. One
gotcha it surfaced: an unauthenticated protected endpoint 401s only for a JSON client — send `Accept: application/json`,
because a browser-style `Accept: */*` matches the SAS chain's text/html entry point and 302-redirects to `/login`.

**Consent (non-trusted clients).** Every stage before IT-6 used `trusted=true` clients to *skip* consent; IT-6 uses a
`trusted=false` client so `/authorize` redirects to `/oauth2/consent` (the `CONSENT_REQUIRED` outcome) instead of
issuing a code. Because a non-trusted client can't complete `login()` in one shot, establish the user's session with a
*separate trusted login client* (`login(...).session()`), then drive the consent client with `authorize(consentClient,
session, scope)` — SSO is tenant-scoped, so the session is recognized across clients. Approve/deny via `submitConsent`,
then `exchange` the code with the triggering `authorize`'s `codeVerifier`. The requested `scope` must be a subset of the
client's *registered* scopes, so a consent client is registered with its RS-prefixed scopes up front.

**Config note.** The app container is run with `-Dcloseauth.session.cookie.secure=false` (in `CloseAuthStack`) so the
session cookie is sent over the suite's plain HTTP — the documented dev/test posture; production keeps `secure=true`.

## The admin API client (`support/AdminApiClient`) — bearer calls against `/v1/**`

`AdminApiClient` (via `adminApi()` on the base class) is the thin, bearer-authenticated counterpart to `OAuthFlowClient`
— no PKCE/cookies/redirects, just `Authorization: Bearer <token>` calls plus the reusable pieces every admin-surface
stage needs. Obtain tokens with `platformAdminToken()` (base class) or `oauthFlow().login(...)` for a `TENANT_ADMIN`
user.

| Method | Use |
|---|---|
| `get/post/postJson/delete(token, path, pathParams…)` | Raw bearer request → REST Assured `Response` (assert status/body yourself). |
| `problemCode(response)` *(static)* | Extract the RFC 7807 domain error `code` (e.g. `tenant_role.last_admin`) — assert the *specific* error, not just the HTTP status. |
| `tenantRoleId(token, tenantId, roleName)` | Find a tenant role's id by name (`GET .../roles`). |
| `assignTenantRole(token, tenantId, userId, roleName)` | Look up the role by name + grant it (→ returns the role id). The "find-a-role-by-name, assign-it" pattern every RBAC stage needs. |
| `provisionActiveTenant(platformToken)` · `registerConfidentialClient(platformToken, tenantId)` · `createActiveUser(platformToken, tenantId, email, password)` | Shared admin-API **fixture builders** — used by every flow/RBAC journey so none re-implement them. |
| `setRegistrationMode(platformToken, tenantId, mode)` | `PUT .../registration-config` to set a tenant's registration mode (`OPEN`/`EMAIL_VERIFIED`/`ADMIN_APPROVED`/`INVITE_ONLY`); asserts 200 + the echoed mode. The fixture step every registration-mode journey needs. |
| `registerClient(platformToken, tenantId, clientId, clientName, trusted, scopes)` · `resourceServerIdBySlug(token, tenantId, slug)` · `addScope(token, tenantId, rsId, scopeName, description, requiresConsent, isDefault)` | Consent-fixture builders (IT-6): the general `registerClient` (used for a `trusted=false` client with explicit RS-prefixed scopes) plus RS-scope catalog management. **NB** the auto-created RS slug is derived from `clientName` (`toSlug`) — pass a clean-slug name so the `{slug}:scope` strings are predictable. |
| `patchJson(token, path, body, pathParams…)` | Generic bearer `PATCH` (IT-7) — for the RS / scope / application-role update endpoints. |
| `getQuery(token, path, queryParams, pathParams…)` | Generic bearer `GET` with query parameters (IT-8) — for the audit query API and any filtered list endpoint; null-valued filters are omitted. |
| `createResourceServer(platformToken, tenantId, slug, name, audienceIdentifier)` · `createApplicationRole(platformToken, tenantId, rsId, name)` | Application-tier RBAC fixture builders (IT-7): a standalone Resource Server and an RS-scoped application role (→ ids). |

**Getting a tenant-admin token:** `createActiveUser(...)` → `assignTenantRole(..., "TENANT_ADMIN")` →
`oauthFlow().login(clientId, secret, email, password)`. The logged-in access token then carries
`tenant_roles=[…,TENANT_ADMIN]` and passes `@RequiresTenantAccess` for that tenant only.

## Layout

```
src/test/java/com/anterka/closeauth/it/
  support/
    CloseAuthStack.java   – Testcontainers orchestration (Postgres+Redis+Mailpit+app), JVM singleton
    IntegrationTest.java  – base class: boots the stack, configures REST Assured, exposes helpers (db, mailpit, oauthFlow, adminApi, platformAdminToken)
    OAuthFlowClient.java  – drives the real Authorization Code + PKCE flow (see section above) — highest-reuse helper
    AdminApiClient.java   – thin bearer client for /v1/** + RFC 7807 code helper + role/fixture builders (see section above)
    Jwt.java              – black-box JWT claims reader (no signature verification)
    Emails.java           – extract a link secret from an email body (linkToken = token=; linkParam(…) for e.g. invite=)
    MailpitClient.java    – Mailpit REST client + waitForMessageTo(...) polling helper
    MailpitMessage.java   – captured-email view (recipients, subject, text)
    Db.java               – minimal read-only JDBC query helper
    Fixtures.java         – unique emails / slugs / client ids (test isolation)
  test/resources/docker-java.properties – pins the Docker API version (see Docker API compatibility note)
  journeys/
    EmailVerifiedRegistrationJourneyTest.java  – IT-1: registration → email code → verify → login → DB
    CoreAuthSsoLogoutJourneyTest.java          – IT-2: login + token claims, SSO same-tenant, SSO refused cross-tenant, logout cascade
    CoreAdminRbacGuardJourneyTest.java         – IT-3 + IT-4: platform-token shape, cross-tenant admin guard, last-admin invariant (both paths + sequential-suspension regression)
    MagicLinkAndPasswordResetJourneyTest.java  – IT-4: magic-link login (amr/idp) + password-reset revocation cascade
    RegistrationModesJourneyTest.java          – IT-5: OPEN / ADMIN_APPROVED / INVITE_ONLY registration modes (with IT-1's EMAIL_VERIFIED = 4/4)
    ConsentJourneyTest.java                    – IT-6: OAuth2 consent (non-trusted client, context correctness, auto-grant persistence, deny, admin list/revoke)
    AppRbacAndResourceServerJourneyTest.java   – IT-7: RS + scope management (uniqueness, immutability, CRUD) + application-tier RBAC (roles, cross-RS rejection, assign/revoke)
    AuditQueryJourneyTest.java                 – IT-8: audit query API (filters, to-exclusive time range, pagination, tenant isolation both directions, platform cross-tenant)
    TenantLifecycleAndPlatformAdminJourneyTest.java – IT-9: tenant suspend/soft-delete cascade (pre-existing-token revocation) & platform-admin CRUD (token-kill on suspend, role claims)
    TenantBrandingJourneyTest.java             – IT-10: public branding resolution (exhaustive leak-nothing, enum-safe) + admin GET/PUT (validation, tenant-scoping, audit)
    ProtocolCompletenessJourneyTest.java       – IT-11: real JWKS signature verification (+tamper control), discovery, /userinfo, RFC 7009 revoke, refresh positive + whole-family replay, OTP lockout, /connect/logout
```

## Scope (IT-1)

One journey only — chosen to exercise every piece of plumbing (image build + boot, HTTP, Mailpit capture + code
extraction, JDBC assertion, clean teardown/isolation): **EMAIL_VERIFIED self-registration → read the emailed code →
verify → login → assert `ACTIVE`/`email_verified` in Postgres.** Broader feature coverage comes in later stages. There
is no CI wiring yet (a later stage).

# CloseAuth — Product Vision & Implementation Foundation

**Version:** 2.0
**Status:** Foundation Document — supersedes v1.0
**Product Type:** Hybrid IAM Platform (Developer-first + Enterprise IAM + AI-native)
**Document Owner:** CloseAuth Team

> This document replaces v1.0 in its entirety. The v1.0 document captured product intent but underspecified the architectural commitments needed to build a production-grade, standards-compliant IDP. v2.0 closes those gaps explicitly so that downstream phases (database design, service contracts, refactor plans) inherit a coherent, opinionated foundation rather than re-litigating the same questions later.
>
> Every architectural decision in this document was made deliberately, with the failure mode of the alternative weighed. Where a decision was deferred, it is named as deferred rather than left ambiguous.

---

## Table of Contents

1. Executive Summary
2. Product Vision
3. Problem Statement
4. Target Market & Positioning
5. Core Product Principles
6. Deployment Model
7. Architectural Foundations (the v1.0 gaps, now decided)
    - 7.1 Tenant as a First-Class Entity
    - 7.2 Signing Key Strategy
    - 7.3 Token Lifecycle, Rotation, and Revocation
    - 7.4 Identity Provider Federation
    - 7.5 Authorization Server Session Model
    - 7.6 Resource Server and Audience Model
    - 7.7 Token Exchange and Delegated Authority
    - 7.8 Admin API as a Product Surface
    - 7.9 Three-Tier RBAC Model
    - 7.10 AI-Ready Identity Architecture
    - 7.11 Audit Log as a Tier-1 Product
8. MVP Scope
9. Features Explicitly Excluded From MVP
10. Technical Architecture
11. Multi-Tenancy Model (consolidated)
12. Token Model (consolidated)
13. Security Requirements
14. Scalability Goals
15. Monetization Strategy
16. Engineering Risks
17. Development Phases
18. Immediate Next Documents To Create
19. Final Product Direction

---

## 1. Executive Summary

CloseAuth is a multi-tenant Identity and Access Management (IAM) platform that combines:

- The developer experience and simplicity of modern hosted auth providers
- The flexibility, control, and self-hosting freedom of enterprise IAM platforms
- First-class architectural support for AI-agent authentication and delegated authority
- Both SaaS-managed and self-hosted deployment models
- Deep observability, audit, and customization as product-tier features

CloseAuth is built around **OAuth 2.1** and **OpenID Connect (OIDC)** as the primary standards, with **RFC 8693 (Token Exchange)**, **RFC 7662 (Token Introspection)**, **RFC 7807 (Problem Details)**, and the evolving **Model Context Protocol (MCP)** authorization patterns as additional standards commitments.

The product is structurally a single shared Authorization Server (one realm, in Keycloak terminology) that hosts many Tenants (one Tenant per customer company, equivalent to a Keycloak Organization or an Auth0 Organization). Tenants own Clients, Users, Resource Servers, branding, registration policy, and roles. Tenant context is encoded into every issued token as a first-class claim.

---

## 2. Product Vision

> **Modern, AI-ready identity infrastructure without SaaS lock-in or enterprise complexity.**

CloseAuth aims to occupy the middle ground between:

- **Hosted SaaS auth (Auth0, Clerk, Stytch, WorkOS):** Excellent developer experience and short time-to-integrate, but expensive at scale ("Identity Tax"), restrictive on customization, and creates vendor lock-in.
- **Self-hosted IAM (Keycloak, Zitadel, Authentik):** Full control and zero per-user pricing, but high operational complexity, slow upgrades, and weak modern frontend integration.

The intended outcome is a platform that:

- Is standards-compliant by construction
- Ships with developer experience equivalent to hosted providers
- Self-hosts cleanly via Docker (and Kubernetes/Helm later)
- Treats AI-agent identity as a first-class concern, not an afterthought
- Treats admin operations, audit, and observability as products, not as ops tooling

---

## 3. Problem Statement

### 3.1 Problems with Hosted SaaS IAM

- MAU-based pricing creates an Identity Tax that scales unfavourably
- Vendor lock-in via proprietary user formats, role models, and admin APIs
- Difficult user migration in/out
- Restricted customization (branding, custom flows, custom storage)
- No control over infrastructure or data residency

### 3.2 Problems with Self-Hosted IAM

- High operational complexity (Keycloak requires meaningful platform-engineering effort)
- Difficult version upgrades, particularly across major versions
- Steep developer learning curve
- Heavy infrastructure footprint relative to product value at small/mid scale
- Modern frontend integration (Vue/React/SPA) is consistently weak across the OSS options

### 3.3 What CloseAuth Solves

A platform that gives developers the integration experience of a SaaS provider, the control surface of a self-hosted IAM, the architectural commitments of a production-grade IDP, and a forward-looking AI-agent identity model — all under one consistent product.

---

## 4. Target Market & Positioning

### 4.1 Primary Initial Customers (Phase 1–2)

- Startups
- SaaS builders
- Developer teams
- Internal enterprise platforms

### 4.2 Secondary Customers (Phase 3+)

- Large enterprises
- Compliance-heavy organizations
- Multi-region deployments
- IAM migration projects (moving off Auth0/Okta)

### 4.3 Positioning Statement

CloseAuth combines:

- The integration simplicity of Clerk/Auth0
- The control and flexibility of Keycloak
- First-class AI-agent identity (a category Keycloak/Auth0 do not yet address natively)
- Self-hosting freedom without operational pain

---

## 5. Core Product Principles

These principles govern every architectural and product decision. Any proposal that violates one of these must explicitly justify the trade-off.

1. **Developer First** — clean APIs, OpenAPI documentation, consistent error model, easy onboarding, operational simplicity.
2. **Strict Standards Compliance** — OAuth 2.1, OIDC, RFC 8693, RFC 7662, RFC 7807, and MCP authorization patterns. Proprietary extensions are documented as extensions, not as default behavior.
3. **Multi-Tenant by Design** — Every query, service contract, token, and audit record carries tenant context. Tenant isolation is enforced at the data, service, and token layers redundantly.
4. **AI-Ready Identity** — AI agents are first-class principals with their own identity records, delegated-authority semantics, and consent grants. Token Exchange (RFC 8693) is the mechanism.
5. **Audit Is a Product** — Audit log is structured, immutable, queryable, and exportable. Not an ops afterthought.
6. **Operational Simplicity** — Modular monolith deployed as a small number of containers, no Kafka/etcd/Vault dependencies in MVP. Scale-out is a Phase 3+ concern, not a Phase 1 mandate.
7. **SaaS + Self-Hosted** — Both deployment modes are first-class. No feature ships that only works in one.

---

## 6. Deployment Model

### 6.1 SaaS Deployment

CloseAuth-hosted, managed multi-tenant. Tenants sign up via a marketing site, get a tenant slug, and integrate immediately.

### 6.2 Self-Hosted Deployment

Customer-hosted via:

- Docker (MVP)
- Kubernetes via Helm chart (Phase 3+)

A self-hosted CloseAuth deployment is structurally identical to the SaaS deployment — same code, same schema, same APIs — except that the deploying customer is the sole owner of the platform and can choose to operate it as single-tenant or multi-tenant internally.

---

## 7. Architectural Foundations

This section is the substantive addition over v1.0. Each subsection captures a foundational decision that shapes the rest of the platform. These are not features — they are commitments that constrain feature design.

### 7.1 Tenant as a First-Class Entity

**Decision:** Tenant is the top-level customer entity in CloseAuth. CloseAuth-the-platform is structurally a single shared Authorization Server (one realm, in Keycloak terms); each customer company is a Tenant (equivalent to a Keycloak Organization or an Auth0 Organization) that lives inside this shared realm.

**Lifecycle states:**

`PROVISIONING → ACTIVE → SUSPENDED → DELETED`

- `PROVISIONING` — Sign-up in progress, not yet usable.
- `ACTIVE` — Normal operation.
- `SUSPENDED` — Tokens rejected, login disabled, data preserved. Admin or billing-initiated.
- `DELETED` — Soft-deleted with retention window, then hard-purged per platform retention policy.

Trial is handled as an attribute of the billing plan attached to an `ACTIVE` tenant, not as a distinct lifecycle state.

**Admin model:** Multiple `TENANT_ADMIN` users per tenant, no hierarchy. The tenant cannot be left with zero `TENANT_ADMIN` users — removing the last admin is rejected at the API layer.

**Identifier scheme:**

- **`tenant_id` (UUID)** — Immutable primary key. This is what tokens carry, what foreign keys reference, what audit logs record.
- **`slug` (mutable string)** — Human-readable identifier for URLs and display. Renames are allowed; tokens are unaffected because they carry the UUID, not the slug.

**Ownership graph — a Tenant owns:**

- Clients (OAuth2 apps registered under it)
- Users (per-tenant user pool)
- Resource Servers (APIs the tenant protects)
- Branding / themes
- Registration policy
- Roles (tenant-level and application-level)
- Audit logs scoped to its activities
- Federation / external IdP connections (Phase 2)
- AI agent identities (Phase 4)

**Platform owns (not Tenant):**

- Signing keys (Phase 1)
- Platform-level admin users (CloseAuth staff)
- Billing plan catalog

### 7.2 Signing Key Strategy

**Decision (MVP):** Platform-global RSA keypair. One `iss` value, one JWKS endpoint serving the entire platform.

**Issuer URL pattern:** Reserve `https://auth.closeauth.io/t/{slug}` as the per-tenant issuer pattern structurally, even though MVP issues with `https://auth.closeauth.io` only. This preserves the migration path to per-tenant keys without breaking existing clients.

**JWKS behavior:** The JWKS endpoint publishes current + previous keys with distinct `kid` values from Phase 1. This makes key rotation a non-event for resource servers.

**Phase 4 evolution:** Per-tenant signing keys as an opt-in feature for enterprise tenants who require key control. The schema and URL structure are reserved for this; the feature is built when an enterprise customer requests it.

### 7.3 Token Lifecycle, Rotation, and Revocation

**Strategies adopted:**

1. **Short access-token TTL** — accept a small revocation window in exchange for simplicity and performance.
2. **Refresh token rotation with replay detection** — mandatory from Phase 1.
3. **Introspection-backed revocation** — Redis-backed denylist consulted via the `/oauth2/introspect` endpoint by resource servers that need instant revocation.

**Not adopted:** Universal stateless-with-denylist (denylist consulted on every API call by every resource server). This adds latency to every call for a feature most resource servers don't need.

**TTL defaults:**

- **Access token:** 5 minutes. Per-tenant configurable in Phase 2.
- **Refresh token:** 14 days, sliding window. Per-tenant configurable in Phase 2.

**Rotation and replay detection:**

- Every use of a refresh token issues a new refresh token and invalidates the old one.
- If an already-used refresh token is ever presented again, the entire token family (every descendant of the original) is revoked. The user must re-authenticate.
- Replay events generate a high-priority audit event.

**Introspection:**

- `/oauth2/introspect` is live and standards-compliant.
- A Redis revocation list (keyed by user ID, with TTL equal to the access token TTL) is consulted on each introspection call.
- Admin actions (suspend user, revoke session, suspend tenant) populate the revocation list immediately.
- Resource servers handling sensitive operations are expected to introspect; non-sensitive operations validate the JWT locally and accept the 5-minute revocation window.

**Phase 4 hook:** Strategy 3 is the foundation that makes AI agent tokens instantly revocable. Agent token revocation cuts the consent grant; existing tokens are dead within 5 minutes via the same introspection mechanism.

### 7.4 Identity Provider Federation

**Decision:** Schema-prepare in MVP, implement as a named Phase 2 deliverable.

**Distinction from social login:**

- **Social login** (in-scope MVP) — CloseAuth's pre-configured Google/GitHub/etc. integrations, available to all tenants uniformly.
- **OIDC federation** (Phase 2) — A tenant connects its own OIDC IdP (the customer's Okta, Google Workspace, internal IdP) so that users in that tenant authenticate upstream and CloseAuth issues its own tokens after verification.
- **SAML / LDAP / AD** (Phase 4) — Same `user_identities` abstraction, different protocols.

**Schema commitments (MVP):**

- `users` is decoupled from credentials. A `users` row no longer implies a password.
- `user_identities` table links users to credential sources: `(user_id, idp_type, idp_subject, idp_connection_id, metadata)` where `idp_type` ∈ `{LOCAL_PASSWORD, SOCIAL_GOOGLE, SOCIAL_GITHUB, OIDC_FEDERATED, SAML, AGENT_KEY, ...}`.
- `tenant_idp_connections` table exists but is empty in MVP. Holds tenant-configured upstream IdP definitions in Phase 2.
- Token claims include an `idp` source claim so resource servers can apply policy (e.g., "require MFA unless logged in via corporate IdP").

**Phase 2 deliverable (named):** OIDC federation feature build-out — connection management UI, JIT user provisioning, claim mapping, attribute-to-role mapping.

**Phase 4 reuse:** The `user_identities` abstraction also hosts AI agent identities (`AGENT_KEY`). Future SAML/LDAP plug into the same model.

### 7.5 Authorization Server Session Model

**Decision:** Tenant-scoped Authorization Server sessions, Redis-backed via Spring Session.

**Why this matters:** Without an Auth Server-level session, "SSO" across a tenant's multiple client apps does not exist — each app's OAuth dance is independent. With it, a user logs in once and is silently recognized across all the tenant's apps. This is the Google/GitHub behavior that defines what users expect "SSO" to feel like.

**Session scope:** Tenant-scoped. A login establishes a session tied to a specific tenant. A single browser may hold sessions for multiple tenants concurrently (multi-tab use case), but no single session spans tenants. Cross-tenant silent SSO is explicitly disallowed.

**Session storage:** Redis-backed via Spring Session. Opaque session ID in the cookie; server-side state in Redis.

**Timeouts:**

- **Idle timeout:** 1 hour (default)
- **Absolute timeout:** 12 hours (default)
- **Remember-me:** Optional 30-day absolute, still respecting idle timeout
- All four values are per-tenant configurable in Phase 2; not configurable in MVP.

**Logout:**

- OIDC RP-initiated logout (`/oidc/logout` endpoint) kills the Auth Server session immediately.
- OIDC back-channel logout notifications are dispatched to every client app that had active tokens from the killed session.
- Individual client app tokens die naturally within the access token TTL (5 minutes) of the session kill.

**Per-device sessions:** Each browser/device gets its own Auth Server session. A `GET /v1/me/sessions` endpoint lists all active sessions for a user and allows individual revocation.

**Phase 4 hook:** AI agent delegated authority can be anchored to the user's Auth Server session lifecycle — when the user logs out, agent authority dies with the session.

### 7.6 Resource Server and Audience Model

**Decision:** Resource Server is a first-class entity owned by Tenant, peer to Client.

**Distinction:**

- **Client** — A web app, SPA, mobile app, or M2M caller that requests tokens.
- **Resource Server** — An API that protects its endpoints with CloseAuth-issued tokens, declares scopes, and acts as a token `aud` target.

These can be the same logical service (a monolith that exposes both a UI client and an API) or completely different services.

**Hybrid UX model:**

- When a Client is registered, CloseAuth auto-creates a 1:1 Resource Server tied to it. The simple-onboarding case stays simple.
- Tenants with explicit multi-app or shared-API scenarios can register standalone Resource Servers separately.
- A `client_authorized_resource_servers` join table tracks which Resource Servers each Client can request tokens for.

**Scope naming convention:**

- **Platform-level scopes** are bare: `openid`, `profile`, `email`, `offline_access`, `client.create`, `tenants:read`, `tenants:write`, etc.
- **Tenant-Resource-Server scopes** are prefixed: `{rs_slug}:{scope_name}` — e.g., `todomaster-api:read`, `cryptotracker-api:portfolio:write`.

**Token `aud` claim:** Resource Server's `audience_identifier`, NOT the requesting Client's `client_id`. This is what OAuth2 actually intends.

**Schema commitments:**

- `resource_servers` table owned by Tenant: `id`, `tenant_id`, `slug`, `name`, `audience_identifier`, `created_at`.
- `resource_server_scopes` table: `resource_server_id`, `scope_name`, `description`, `is_default`, `requires_consent`.
- `client_authorized_resource_servers` join table.

**M2M consequence:** M2M tokens correctly target a Resource Server. The CloseAuth platform admin API is itself a Resource Server (`closeauth-admin-api`, platform-owned) with scopes like `tenants:read`, `clients:write`.

### 7.7 Token Exchange and Delegated Authority

**Decision:** Commit to **RFC 8693 (Token Exchange)** as the standard mechanism for delegated authority. Endpoint built in Phase 3; schema and claims reserved from Phase 1.

**Token claims reserved from Phase 1:**

- **`act`** — Actor identity. May be absent (direct action), present (delegated), or a chain (`act.act` for multi-hop delegation). Even in MVP, the claim is reserved and resource server documentation notes its presence as possible.

**Audit log shape from Phase 1:**

- `subject_user_id` — Whom the action was performed on behalf of.
- `actor_user_id` and `actor_client_id` — Who or what actually performed the action.
- In direct actions (no delegation), subject and actor are the same value. The schema is consistent regardless.

**Phase 3 deliverables (named):**

- RFC 8693 Token Exchange endpoint
- Impersonation (TENANT_ADMIN or platform support staff acting AS a user for debugging), implemented as a Token Exchange use case with an explicit `impersonation` indicator in the resulting token

**Phase 4 use:** AI agent identities use Token Exchange as their primary token issuance flow. Agent tokens always carry `act` identifying the agent and `sub` identifying the represented user.

**MVP service-to-service pattern (until Phase 3):** Client Credentials grant with a dedicated service identity. Documented as the recommended pattern; Token Exchange is positioned as a Phase 3 upgrade.

### 7.8 Admin API as a Product Surface

**Decision:** The admin API is a first-class, public, versioned, documented product. The admin dashboard UI is just its first consumer. The BFF is just its second.

**URL structure:** Resource-oriented REST with tenant explicit in the path:

```
/v1/tenants/{tenant_id}/users/{user_id}
/v1/tenants/{tenant_id}/clients
/v1/tenants/{tenant_id}/resource-servers
/v1/tenants/{tenant_id}/audit-events
/v1/platform/tenants            (platform-admin only)
/v1/me                          (current-principal endpoints)
```

**Authentication:** OAuth2 only for MVP. The admin API is itself a Resource Server (`closeauth-admin-api`, platform-owned). Tokens are obtained via Client Credentials (for machine clients) or Authorization Code (for the dashboard UI). API keys are explicitly NOT introduced in MVP; revisited in Phase 2 based on customer feedback.

**Versioning policy:**

- Semver at the URL level (`/v1`, `/v2`)
- Additive changes are non-breaking and ship within the current major version
- Breaking changes get a new major version
- Deprecated versions are supported for a documented window

**Error model:** RFC 7807 Problem Details for HTTP APIs:

```json
{
  "type": "https://docs.closeauth.io/errors/tenant-suspended",
  "title": "Tenant suspended",
  "status": 403,
  "detail": "Tenant acme-corp is suspended; admin operations are read-only.",
  "instance": "/v1/tenants/acme-corp/users/u_abc"
}
```

**Rate limiting:** Per-tenant, per-endpoint, with `Retry-After` headers on 429 responses.

**Phase 2 deliverables:** Public OpenAPI spec, public API documentation site, optional API keys based on customer feedback.

**Discipline implication:** Every service method has a documented public endpoint. Methods without a public endpoint are either given one or deleted. This eliminates the dead-code pattern (current `ClientService.create()` exists with no controller mapping) that the v1.0 architecture allowed.

### 7.9 Three-Tier RBAC Model

**Decision:** Three distinct authorization layers, each separately modeled. Permissions are Resource Server scopes (no parallel permission catalog).

**The three layers:**

| Layer | Scope of authority | Examples |
|---|---|---|
| **Platform** | CloseAuth-the-platform itself | `PLATFORM_ADMIN`, `PLATFORM_SUPPORT` |
| **Tenant** | One specific tenant | `TENANT_ADMIN`, `TENANT_MEMBER`, `BILLING_ADMIN` |
| **Application** | Within a specific Resource Server's user pool | `PORTFOLIO_VIEWER`, `WORKSPACE_OWNER`, defined by the tenant for their own RS |

**Permissions = Scopes:**

- Resource Server scopes (Section 7.6) ARE the permission catalog.
- A role is a named bundle of scopes plus metadata (description, `is_default`, `requires_consent`).
- Resource servers enforce on the `scope` claim, not on role names. Roles are a convenience abstraction over scope grants.

**Schema commitments:**

- `platform_roles` table + `user_platform_roles` join. Most users have zero rows here; only CloseAuth staff have any.
- `tenant_roles` table + `user_tenant_roles` join, shape `(tenant_id, user_id, role_id)`. Tenants get predefined defaults plus the ability to create custom roles.
- `application_roles` table (already exists in current schema; refactored to link to `resource_server_id` instead of `client_id`).
- `user_application_roles` table, shape `(user_id, resource_server_id, role_id)`.

**Token claim shape:**

```jsonc
{
  "sub": "u_alice_uuid",                       // stable opaque user ID
  "tenant_id": "t_acme_uuid",
  "roles": ["PLATFORM_ADMIN"],                 // platform roles (rare, staff only)
  "tenant_roles": ["TENANT_MEMBER"],           // tenant-layer roles
  "app_roles": [
    {"rs": "todomaster-api", "roles": ["EDITOR"]},
    {"rs": "cryptotracker-api", "roles": ["VIEWER"]}
  ],
  "scope": "openid profile todomaster-api:write cryptotracker-api:read"
}
```

**Role templates / starter packs:** When a tenant is created, default tenant roles and per-RS application roles are pre-populated so onboarding doesn't require defining roles from scratch.

**`GlobalRoleEnum` is deleted in the refactor:**

- `SUPER_ADMIN` → `PLATFORM_ADMIN` in `platform_roles`
- `CLIENT_ADMIN` → `TENANT_ADMIN` in `tenant_roles`
- `END_USER` → no equivalent (the absence of admin roles is the default)

### 7.10 AI-Ready Identity Architecture

**Decision:** AI agents are first-class principals, peer to User and Client. Schema prepared from Phase 1; feature delivered in Phase 4 with explicit MCP IdP compliance.

**What "AI-ready" means concretely (not as marketing):**

- Agents have their own identity records (separate `agents` table, not a flag on `users`)
- Agents authenticate via the same `user_identities` abstraction (`idp_type = AGENT_KEY`) — no parallel credential system
- Agent token issuance uses RFC 8693 Token Exchange (Section 7.7)
- Agent permissions are granted via user consent (OAuth2-standard model)
- Agent tokens are instantly revocable via the introspection mechanism (Section 7.3)
- All architectural choices in earlier phases are constrained to be MCP-compliant

**Schema commitments (Phase 1):**

- `agents` table: `id` (UUID), `tenant_id`, `name`, `agent_type` ∈ `{MCP_SERVER, EMBEDDED_AGENT, WORKFLOW_RUNNER, ...}`, `agent_owner_user_id` (who registered it; responsible party), `represented_user_id` (optional; who it acts on behalf of), `status`, `created_at`.
- `agent_consent_grants` table: `(agent_id, granting_user_id, resource_server_id, granted_scopes, granted_at, revoked_at)` — the audit trail of which user granted which agent which scopes.
- `user_identities` rows for agents with `idp_type = AGENT_KEY`.
- Audit log captures `actor_agent_id` and `granted_consent_id` in addition to subject/actor user fields, for full chain traceability.

**Token claims for agent-issued tokens (Phase 4):**

```jsonc
{
  "sub": "u_alice_uuid",                       // represented user
  "act": {
    "type": "agent",
    "agent_id": "ag_xyz_uuid",
    "agent_name": "Alice's Portfolio Watcher"
  },
  "scope": "cryptotracker-api:portfolio:read", // consented subset only
  "aud": "cryptotracker-api",
  "tenant_id": "t_acme_uuid"
}
```

**Consent model (Phase 4):** Per-registration. A user grants an agent a scope set once; the agent can exchange for tokens within those scopes at any time until the consent is revoked. Per-scope sensitivity policies (some scopes requiring per-exchange consent) deferred to Phase 5+.

**Revocation:** Cutting an `agent_consent_grant` immediately invalidates the consent. Any tokens issued under that consent are dead within 5 minutes via the introspection mechanism.

**MCP commitment:** CloseAuth ships as a "good MCP IdP" by Phase 4. As the MCP authorization standard evolves, CloseAuth's implementation tracks it. Earlier-phase architectural choices (Token Exchange, stable `sub`, scope-based permissions, introspection) are exactly what MCP expects, so the constraint is mostly already satisfied by earlier decisions.

### 7.11 Audit Log as a Tier-1 Product

**Decision:** Audit is treated as a customer-facing product, not as an operational by-product.

**Event taxonomy:** A defined enum of canonical event types with stable, typed schemas per event. Initial categories:

- **Authentication:** `USER_LOGIN_SUCCESS`, `USER_LOGIN_FAILURE`, `USER_LOGOUT`, `TOKEN_ISSUED`, `TOKEN_REVOKED`, `TOKEN_INTROSPECTED`, `REFRESH_TOKEN_ROTATED`, `REFRESH_TOKEN_REPLAY_DETECTED`
- **Identity:** `USER_CREATED`, `USER_UPDATED`, `USER_DELETED`, `PASSWORD_CHANGED`, `MFA_ENROLLED`, `MFA_REMOVED`
- **Tenant:** `TENANT_CREATED`, `TENANT_SUSPENDED`, `TENANT_DELETED`, `TENANT_BRANDING_CHANGED`, `TENANT_REGISTRATION_POLICY_CHANGED`
- **Authorization:** `ROLE_ASSIGNED`, `ROLE_REVOKED`, `CONSENT_GRANTED`, `CONSENT_REVOKED`
- **Client:** `CLIENT_REGISTERED`, `CLIENT_UPDATED`, `CLIENT_DELETED`
- **Resource Server:** `RESOURCE_SERVER_CREATED`, `SCOPE_DEFINED`, `SCOPE_REMOVED`
- **Agent:** `AGENT_REGISTERED`, `AGENT_REVOKED`, `AGENT_CONSENT_GRANTED`, `AGENT_TOKEN_EXCHANGED`
- **Administrative:** `ADMIN_LOGIN`, `PLATFORM_CONFIGURATION_CHANGED`

Each event type has a typed schema. `USER_LOGIN_SUCCESS` always includes `user_id`, `tenant_id`, `client_id`, `ip_address`, `user_agent`, `mfa_method`. Free-form metadata blobs are not used for canonical events.

**Immutability:** Append-only at the database level. The application audit-write role has INSERT-only privileges; UPDATE and DELETE rights are denied for application code. Even a compromise of application code cannot tamper with the log through normal paths.

**Reliability via outbox pattern:** Service code publishes `CloseAuthAuditEvent` (the existing scaffold, now wired). An event handler writes to a local outbox (PG outbox table or Redis Streams) synchronously within the auth-flow transaction. An async worker drains the outbox to the main audit store. Auth flow latency is decoupled from main-store write latency.

**Retention:**

- Default: 90 days hot in PostgreSQL
- Per-tenant configurable on paid plans
- Cold storage tier (S3 or equivalent) for long-term retention: Phase 3 deliverable

**Tenant scoping:** Enforced at the query layer with no exceptions. Platform-admin cross-tenant queries go through a separate endpoint (`/v1/platform/audit-events`) with explicit cross-tenant scope.

**Customer-visible API from MVP:**

```
GET /v1/tenants/{tenant_id}/audit-events
  ?event_type=USER_LOGIN_FAILURE
  &user_id=u_abc
  &from=2026-01-01T00:00:00Z
  &to=2026-02-01T00:00:00Z
  &cursor=...
  &limit=100
```

Bulk export (CSV, JSON) is a Phase 2 deliverable. SIEM webhook firehose (Splunk, Datadog, Elastic ingestion) is a Phase 3 deliverable.

**Phase 4 traceability:** Audit records for agent-initiated actions carry the full chain: `subject_user_id` (Alice), `actor_agent_id` (the agent), `granted_consent_id` (which consent grant authorized this). Schema reserves these fields from Phase 1.

---

## 8. MVP Scope

This section enumerates concrete MVP deliverables. Anything not listed here is either Phase 2+ or deferred.

### 8.1 Authentication Methods

- Email + password (with email as the unique identifier, scoped per-tenant: `(tenant_id, email)` unique)
- Social login (Google, GitHub at minimum)
- Magic links
- M2M (Client Credentials)

### 8.2 OAuth 2.1 / OIDC Flows

- Authorization Code + PKCE (for SPAs and web apps)
- Client Credentials (for M2M)
- Refresh Token (with rotation and replay detection)
- OIDC Discovery, JWKS, UserInfo
- OIDC Dynamic Client Registration (DCR) — already partially implemented

### 8.3 Tenant Management

- Tenant signup and provisioning
- Tenant lifecycle (PROVISIONING → ACTIVE → SUSPENDED → DELETED)
- Multi-admin model
- Tenant slug + UUID

### 8.4 Client Management

- CRUD via the admin API (Section 7.8)
- Auto-created 1:1 Resource Server per Client
- Per-client configuration of redirect URIs, scopes, grant types, token policies

### 8.5 User Management

- Admin CRUD on users
- Self-registration (configurable per-tenant: open, email-verified, admin-approved, invite-only)
- Email verification via OTP (existing flow, with verification tokens persisted properly, not Redis-only)
- Forgot-password / reset-password (with tokens persisted properly)

### 8.6 RBAC (per Section 7.9)

- Three-tier roles: Platform / Tenant / Application
- Permissions as RS scopes
- Default role templates per tenant
- Role assignment APIs

### 8.7 Branding and Customization

- Per-tenant branding (logo, theme, colors)
- Per-Resource-Server consent screens
- Hosted login/registration/reset pages with tenant theme

### 8.8 Resource Servers (per Section 7.6)

- Auto-created 1:1 with each Client
- Standalone Resource Server registration for explicit cases
- Scope catalog management

### 8.9 Sessions (per Section 7.5)

- Auth Server-level tenant-scoped sessions (Redis-backed)
- Multi-device session listing and per-session revocation
- OIDC RP-initiated logout + back-channel logout

### 8.10 Audit (per Section 7.11)

- Defined event taxonomy
- Append-only PG store via outbox
- Customer-facing query API

### 8.11 Admin API (per Section 7.8)

- OAuth2-authenticated, versioned, OpenAPI-described
- Resource-oriented REST with tenant in URL
- RFC 7807 error model

### 8.12 Observability

- Prometheus metrics
- Structured JSON logs
- OpenTelemetry tracing across the SAS, BFF, and Go gateway

---

## 9. Features Explicitly Excluded From MVP

These are intentionally out of scope. They appear in later phases or as future considerations.

- ABAC (Attribute-Based Access Control)
- Complex policy engines / permission graphs
- OIDC federation (Phase 2)
- Enterprise SAML / LDAP / AD (Phase 4)
- Custom domains per tenant (Phase 2)
- Per-tenant signing keys (Phase 4)
- Token Exchange endpoint (Phase 3)
- Impersonation (Phase 3)
- AI-agent registration / consent / token issuance (Phase 4)
- MCP IdP compliance (Phase 4)
- AI threat detection / AI insights / AI recommendations (Phase 4+)
- Workflow engines
- Multi-region deployment
- Full SIEM integrations (Phase 3 webhook firehose; deeper integrations later)
- Distributed microservices architecture (modular monolith for the foreseeable future)
- Cold-storage audit retention (Phase 3)
- Bulk audit export, custom tenant event types (Phase 2)
- API keys for admin API (Phase 2, demand-driven)
- Helm chart / Kubernetes packaging (Phase 3+)

---

## 10. Technical Architecture

### 10.1 Architecture Pattern

**Modular monolith.** Clear domain separation by package, deployable as a single Java process. Microservice extraction is a Phase 3+ option, not a Phase 1 mandate.

### 10.2 Technology Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5+, Spring Authorization Server |
| Frontend | Vue 3 + Vite + TypeScript + Pinia + Tailwind + shadcn-vue |
| BFF / Gateway | Go (chi router) |
| Database | PostgreSQL |
| Cache / Session Store | Redis / Valkey |
| Observability | Prometheus, OpenTelemetry, structured JSON logs |
| Mail | SMTP via configurable provider (Mailtrap sandbox for dev) |

### 10.3 Backend Module Layout (target after refactor)

```
com.anterka.closeauthbackend
├── platform/         (platform-admin concerns, signing keys, system config)
├── tenant/           (Tenant entity, lifecycle, ownership, slug↔UUID)
├── identity/         (Users, user_identities, credentials, federation prep)
├── client/           (OAuth2 clients, configuration, branding)
├── resourceserver/   (Resource Server entity, scope catalog)
├── auth/             (login flows, registration, OTP, password reset)
├── token/            (token issuance, rotation, introspection, revocation list)
├── session/          (Auth Server session model, Redis-backed)
├── rbac/             (platform/tenant/application roles, role templates)
├── agent/            (Phase 1: schema; Phase 4: feature)
├── audit/            (event taxonomy, outbox, query API)
├── admin-api/        (the public admin API surface)
├── common/           (shared config, security, error model, utilities)
└── notification/     (email/SMS)
```

### 10.4 BFF and Gateway

The Go BFF is an OAuth2 confidential client of the Spring Authorization Server. It does NOT have a privileged backdoor; it consumes the public admin API like any other client (Section 7.8). It serves the compiled Vue static frontend and proxies API calls.

---

## 11. Multi-Tenancy Model (consolidated)

**Pattern:** Shared database, shared schema, with `tenant_id` on every tenant-owned table.

**Isolation enforcement is layered:**

1. **Database layer** — Foreign keys to `tenants.id` on every tenant-owned table. Composite uniqueness constraints (e.g., `(tenant_id, email)` on `users`) ensure cross-tenant collisions are impossible.
2. **Service layer** — Every service method that operates on tenant-owned data receives `tenant_id` as an explicit parameter. There is no implicit "current tenant" magic that can be omitted.
3. **Token layer** — Every issued token carries `tenant_id` as a first-class claim. Resource servers can validate tenant context independently of CloseAuth.
4. **Audit layer** — Every audit event is `tenant_id`-scoped. Tenant query APIs cannot return events for a different tenant.

**Tenant resolution:**

- Inbound user-facing requests resolve tenant from the `client_id` parameter in the OAuth2 flow (deterministic lookup `client_id → tenant_id`).
- Admin API requests carry tenant in the URL path (`/v1/tenants/{tenant_id}/...`).
- Token-bearer requests carry tenant in the token's `tenant_id` claim.

**Cross-tenant operations:** Only platform-admin endpoints under `/v1/platform/...` can operate across tenants, and only with explicit platform-admin scopes.

---

## 12. Token Model (consolidated)

Every token issued by CloseAuth carries the following claim shape. This is the canonical reference for token-claim contracts.

### 12.1 Access Token (User context)

```jsonc
{
  // Standard claims
  "iss": "https://auth.closeauth.io",          // platform issuer; future per-tenant: ".../t/{slug}"
  "sub": "u_alice_uuid",                       // STABLE OPAQUE user UUID, never changes
  "aud": "todomaster-api",                     // Resource Server audience_identifier
  "iat": 1719799200,
  "exp": 1719799500,                           // 5 min
  "jti": "tok_xyz",
  "nbf": 1719799200,

  // CloseAuth claims
  "tenant_id": "t_acme_uuid",                  // first-class tenant context
  "client_id": "c_todomaster_spa",             // the requesting client
  "idp": "LOCAL_PASSWORD",                     // credential source (Gap 4)

  // RBAC claims (Section 7.9)
  "roles": [],                                 // platform roles (staff only)
  "tenant_roles": ["TENANT_MEMBER"],
  "app_roles": [
    {"rs": "todomaster-api", "roles": ["EDITOR"]}
  ],
  "scope": "openid profile todomaster-api:read todomaster-api:write",

  // Delegation (Section 7.7) — absent for direct actions, present for delegated
  "act": null
}
```

### 12.2 Access Token (Agent / Delegated context — Phase 4)

```jsonc
{
  "iss": "https://auth.closeauth.io",
  "sub": "u_alice_uuid",                       // represented user
  "aud": "cryptotracker-api",
  "tenant_id": "t_acme_uuid",
  "scope": "cryptotracker-api:portfolio:read", // consented subset
  "act": {
    "type": "agent",
    "agent_id": "ag_xyz_uuid",
    "agent_name": "Alice's Portfolio Watcher"
  }
}
```

### 12.3 ID Token (OIDC)

Standard OIDC claims plus `tenant_id`. Used for client-side identity, not for resource server authorization.

### 12.4 Refresh Token

Opaque. Server-side stored with `(token_hash, user_id, tenant_id, client_id, family_id, parent_token_id, status, expires_at)`. Rotation issues a new token with the same `family_id` and links via `parent_token_id`. Replay detection: if a token with status `USED` is presented, the entire `family_id` is revoked.

---

## 13. Security Requirements

### 13.1 Cryptographic

- Passwords hashed with Argon2id (preferred) or bcrypt with appropriate cost
- TLS everywhere; HSTS on hosted endpoints
- CSP on hosted UI pages
- JWT signing via RS256 (RSA) with publishing of current + previous keys in JWKS for non-disruptive rotation

### 13.2 Standards Compliance

- OAuth 2.1, OIDC, RFC 8693, RFC 7662, RFC 7807
- PKCE required for all public clients (no exceptions)
- Redirect URI exact-match validation; no prefix matching
- CORS safelist per Client
- Strict scope grant rules; no scope upgrade across token boundaries

### 13.3 Abuse Protection

- Rate limiting on login, registration, OTP, password reset (already implemented strategy-pattern style; extend to admin API)
- Exponential backoff on repeated failures
- IP-based and user-based limits, configurable per tenant in Phase 2
- Trusted-proxy-aware IP resolution; fix the `OAuth2RegistrationController.getClientIp()` inconsistency from the current code

### 13.4 Account Lifecycle

- Email verification single-use, hashed at rest
- Password reset tokens single-use, hashed at rest, persisted in DB (not Redis-only as currently)
- Full session invalidation on password change
- Force re-authentication on email change

### 13.5 Tenant-Level

- Tenant suspension immediately revokes all in-flight tokens via introspection
- Cross-tenant data access prevented at DB, service, token, and audit layers

---

## 14. Scalability Goals

### 14.1 Initial Target (Phase 1–2)

- ~10K users across ~100–500 tenants
- Moderate authentication traffic
- Single-region SaaS deployment

### 14.2 Phase 3 Target

- ~100K users across ~1K tenants
- Per-tenant rate limits and quotas
- Cold storage tier for audit retention
- Read replicas for analytics queries

### 14.3 Phase 4+ Target

- Multi-region deployment options
- Per-tenant signing keys for enterprise tenants
- Horizontal scaling of token issuance (the modular monolith can be split here if needed)

---

## 15. Monetization Strategy

### 15.1 SaaS Model

- Subscription tiers
- Free tier for early-stage and small tenants
- Paid tiers unlock: longer audit retention, per-tenant timeout configuration, custom domains, per-tenant signing keys (Phase 4+), SLA commitments

### 15.2 Self-Hosted Model

- Enterprise license for self-hosted deployments
- Custom support contracts
- Enterprise feature flags (e.g., per-tenant signing keys without SaaS dependency)

---

## 16. Engineering Risks

### 16.1 Multi-Tenancy Isolation

Highest-priority risk. Mitigated by layered isolation (Section 11) and by mandatory integration-test coverage of tenant-boundary attacks before any release. The test pyramid commits to "tenant isolation → OAuth2 flows → BFF → frontend → E2E" ordering.

### 16.2 Schema Evolution

The MVP schema commitments in this document are extensive (Tenant, Resource Server, three-tier RBAC, user_identities, agents, agent_consent_grants, audit taxonomy). Getting these wrong is expensive. Risk mitigated by treating Phase 1 as primarily a schema-and-foundation phase rather than a feature phase.

### 16.3 Standards Drift

OAuth 2.1 and MCP authorization are still evolving in some details. Risk mitigated by structuring code around the stable parts of the specs and isolating evolving parts (MCP) behind interfaces.

### 16.4 Performance of Introspection

Strategy 3 (Section 7.3) puts a Redis call in the path of sensitive resource server requests. Risk mitigated by Redis being co-located, denylist staying small (TTL = access token TTL), and resource servers opting in only for sensitive operations.

### 16.5 Outbox Reliability

Audit log outbox pattern (Section 7.11) trades simplicity for reliability. Risk mitigated by monitoring outbox depth and worker health as first-class operational metrics.

---

## 17. Development Phases

Each phase has a goal, a set of named deliverables, and the architectural commitments it relies on from this document.

### Phase 1 — Foundation Refactor (current focus)

**Goal:** Refactor the existing codebase to align with this vision. Establish all schema commitments. Implement the architectural primitives that later phases depend on.

**Deliverables:**

- `tenants` table and lifecycle; refactor existing `oauth2_registered_client` and friends to be tenant-owned
- `user_identities` abstraction; decouple `users` from passwords
- `resource_servers` and `resource_server_scopes`; refactor `aud` claim to use RS audience
- Three-tier RBAC schema; delete `GlobalRoleEnum`
- `agents` and `agent_consent_grants` schema (empty in MVP)
- Audit event taxonomy and outbox infrastructure; wire `CloseAuthAuditEvent`
- Refresh token rotation + replay detection
- Redis revocation list + introspection endpoint backing
- Auth Server-level session model (Spring Session + Redis)
- Token claim contract per Section 12
- Admin API surface (resource-oriented REST, RFC 7807 errors, OpenAPI annotations even if doc site is Phase 2)
- Issuer URL pattern reserves per-tenant slug shape
- JWKS publishes current + previous keys
- Multi-key rotation infrastructure
- Stable opaque `sub` (UUIDs, not username)
- Integration test infrastructure: Testcontainers (PG + Redis), Mailpit for email capture, baseline tests for tenant isolation and OAuth2 flows

### Phase 2 — Developer Platform

**Goal:** Make CloseAuth a product developers actually want to integrate with.

**Deliverables:**

- OIDC federation (the Phase 2 commitment from Section 7.4)
- Admin API: public OpenAPI spec, public documentation site
- Per-tenant configuration of token TTLs, session timeouts, rate limits
- Tenant role management UI, application role management UI, role templates
- Branding system polish; custom domains
- SDKs (Java, Node/TS, Python) wrapping the admin API
- Sample applications (Vue, React, Spring) integrating with CloseAuth
- Bulk audit export
- Optional admin API keys (if demand confirmed)

### Phase 3 — Observability, Operations, Token Exchange

**Goal:** Production-grade operational features and the Token Exchange foundation.

**Deliverables:**

- RFC 8693 Token Exchange endpoint
- Impersonation as a Token Exchange use case
- SIEM webhook firehose for audit events
- Cold-storage audit retention tier
- Service-to-service auth via Token Exchange (replacing the Phase 1–2 Client Credentials pattern as the recommended approach)
- Helm chart / Kubernetes packaging
- High-availability deployment guides

### Phase 4 — AI Identity, Enterprise Expansion

**Goal:** Deliver the AI-native commitment and the first wave of enterprise features.

**Deliverables:**

- Agent registration, consent grant flow, agent-targeted Token Exchange
- MCP IdP compliance
- Per-tenant signing keys (opt-in for enterprise tenants)
- Enterprise SAML support
- LDAP / AD integration
- Multi-region deployment options
- Advanced authorization (ABAC if customer demand confirms it; otherwise deferred)

---

## 18. Immediate Next Documents To Create

Following completion of this Vision v2.0:

### Foundational

- **Database Schema v2** — concrete DDL reflecting every architectural commitment in Section 7
- **Token Lifecycle Specification** — Phase 1 implementation details for rotation, replay detection, introspection, revocation list
- **Multi-Tenancy Implementation Guide** — how tenant context is captured and enforced at every layer
- **Audit Event Catalog** — full schema per canonical event type

### Refactor Planning

- **Phased Refactor Plan** — the actual sequenced refactor of the current codebase onto this vision (the immediate next deliverable)
- **Migration Strategy** — how to evolve the current schema into the target schema without losing existing data

### Product

- **Admin API OpenAPI Specification** — initial draft based on Section 7.8
- **Developer Integration Guide** — how a customer integrates CloseAuth into their app

### Engineering

- **Coding Standards** — package layout, naming conventions, layering rules
- **CI/CD Pipeline Design** — test pyramid execution, deployment gates
- **Threat Model** — based on this Vision's architecture

---

## 19. Final Product Direction

CloseAuth is not "another auth provider." It is intended to be:

> **A modern, AI-ready identity infrastructure platform that balances developer experience, enterprise flexibility, standards compliance, and operational simplicity — with explicit architectural commitments to the IDP patterns that production-grade systems (Keycloak, Auth0, WorkOS) have proven necessary, and explicit commitments to the next-generation patterns (Token Exchange, agent identity, MCP) that the AI era requires.**

The commitments in Section 7 are the difference between an auth provider that ages well and one that quietly accumulates architectural debt until it has to be rewritten. Future product decisions should be measured against this document; any proposal that violates a Section 7 commitment without explicit justification should be reconsidered.
-- =============================================================================
-- CloseAuth — Phase 1 target schema (Stage 1)
--
-- Single Flyway baseline migration realizing every schema commitment from
-- Section 7 (Architectural Foundations) and Section 12 (Token Model) of
-- CLOSEAUTH_PRODUCT_VISION_V2.md.
--
-- Conventions:
--   * New PKs: UUID DEFAULT gen_random_uuid().
--   * All timestamps on new tables: TIMESTAMPTZ (no naive timestamps).
--     EXCEPTION: Spring Authorization Server (SAS) standard columns keep SAS's
--     native types (incl. naive `timestamp`) so the JDBC/JPA mappers match.
--   * Enum-like columns: VARCHAR + inline CHECK. No native ENUM types.
--   * Every tenant-owned table carries tenant_id -> tenants(id).
--   * Every FK column is indexed unless it is already the leading column of a
--     PK or composite UNIQUE (that index serves the prefix).
--   * Every FK has an explicit ON DELETE action.
--
-- Forward references (a table referencing one defined later, given the required
-- section ordering) are resolved in SECTION 14 via ALTER TABLE.
-- =============================================================================


-- ===== SECTION: 1 — Extensions and setup =====

-- pgcrypto provides gen_random_uuid(). On PG13+ it is in pg_catalog, but the
-- extension declaration is defensive and harmless on older/managed setups.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";


-- ===== SECTION: 2 — Platform layer =====

-- Platform-level roles (CloseAuth staff only). Section 7.9.
CREATE TABLE platform_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) NOT NULL UNIQUE,          -- e.g. PLATFORM_ADMIN, PLATFORM_SUPPORT
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Assigns platform roles to users. Most users have zero rows here.
-- user_id / granted_by_user_id FKs -> users are added in SECTION 14 (users is
-- defined in SECTION 4; this is a forward reference).
CREATE TABLE user_platform_roles (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL,
    platform_role_id   UUID NOT NULL REFERENCES platform_roles(id) ON DELETE RESTRICT,
    granted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    granted_by_user_id UUID,                          -- who granted this role; FK added in SECTION 14
    CONSTRAINT uq_user_platform_roles UNIQUE (user_id, platform_role_id)
);
CREATE INDEX idx_user_platform_roles_platform_role_id   ON user_platform_roles (platform_role_id);


-- ===== SECTION: 3 — Tenant layer =====

-- The first-class Tenant entity. Section 7.1.
CREATE TABLE tenants (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- immutable; carried by tokens & FKs
    slug       VARCHAR(63) NOT NULL UNIQUE,                 -- mutable, DNS-safe, used in URLs/display
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING'
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ                                  -- set on transition to DELETED; hard-purge job uses this
);
CREATE INDEX idx_tenants_status ON tenants (status);


-- ===== SECTION: 4 — Identity layer =====
-- Ordered users -> tenant_idp_connections -> user_identities so that
-- user_identities.idp_connection_id can reference tenant_idp_connections inline.

-- Users, decoupled from credentials (NO password_hash here). Section 7.4.
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- carried by the token `sub` claim
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    email         VARCHAR(255) NOT NULL,                       -- NOT globally unique; see composite below
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone         VARCHAR(20),
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    first_name    VARCHAR(100),
    last_name     VARCHAR(100),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email)  -- per-tenant email uniqueness; Section 11
);
-- tenant_id is the leading column of uq_users_tenant_email, so that index already
-- serves `WHERE tenant_id = ?`; no standalone tenant_id index is created.
CREATE INDEX idx_users_status ON users (status);

-- External OIDC/SAML connections per tenant. Empty in MVP; populated in Phase 2.
CREATE TABLE tenant_idp_connections (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    connection_type VARCHAR(50) NOT NULL
        CHECK (connection_type IN ('OIDC', 'SAML', 'GOOGLE_WORKSPACE', 'AZURE_AD')),
    name            VARCHAR(200) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    config          JSONB NOT NULL,                    -- issuer URL, client id/secret refs, claim mappings
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,
    CONSTRAINT uq_tenant_idp_connections_tenant_name UNIQUE (tenant_id, name)
);
-- tenant_id is the leading column of the composite UNIQUE above -> no standalone index.

-- Links a user to a credential source. Section 7.4.
CREATE TABLE user_identities (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,  -- denormalized for isolation checks
    idp_type          VARCHAR(50) NOT NULL
        CHECK (idp_type IN ('LOCAL_PASSWORD', 'SOCIAL_GOOGLE', 'SOCIAL_GITHUB', 'OIDC_FEDERATED', 'SAML', 'AGENT_KEY')),
    idp_subject       VARCHAR(500),                    -- upstream identifier; null for LOCAL_PASSWORD/AGENT_KEY
    idp_connection_id UUID REFERENCES tenant_idp_connections(id) ON DELETE RESTRICT,  -- only for federated identities
    password_hash     VARCHAR(255),                    -- only when idp_type = LOCAL_PASSWORD
    password_algo     VARCHAR(50),                     -- e.g. bcrypt, argon2id
    metadata          JSONB,                           -- provider-specific attributes
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT uq_user_identities_user_idp UNIQUE (user_id, idp_type, idp_subject)
);
-- user_id is the leading column of the composite UNIQUE above -> no standalone index.
CREATE INDEX idx_user_identities_tenant_id         ON user_identities (tenant_id);
CREATE INDEX idx_user_identities_idp_type          ON user_identities (idp_type);
CREATE INDEX idx_user_identities_idp_connection_id ON user_identities (idp_connection_id);


-- ===== SECTION: 5 — Client layer (OAuth2 registered clients + tenant ownership) =====

-- Spring Authorization Server's standard table, extended with tenant ownership.
-- SAS-native columns keep SAS's types (incl. naive `timestamp`) to match the
-- JDBC/JPA client mapper precisely. There is NO separate client_ownership table
-- in the new schema; ownership is expressed via tenant_id + tenant admin roles.
CREATE TABLE oauth2_registered_client (
    id                            VARCHAR(100) NOT NULL,
    client_id                     VARCHAR(100) NOT NULL,
    client_id_issued_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret                 VARCHAR(200) DEFAULT NULL,
    client_secret_expires_at      TIMESTAMP    DEFAULT NULL,
    client_name                   VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types     VARCHAR(1000) NOT NULL,
    redirect_uris                 VARCHAR(1000) DEFAULT NULL,
    post_logout_redirect_uris     VARCHAR(1000) DEFAULT NULL,
    scopes                        VARCHAR(1000) NOT NULL,
    client_settings               VARCHAR(2000) NOT NULL,
    token_settings                VARCHAR(2000) NOT NULL,
    -- CloseAuth additions:
    tenant_id                     UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT uq_oauth2_registered_client_tenant_client UNIQUE (tenant_id, client_id)
);
-- tenant_id is the leading column of the composite UNIQUE above -> no standalone index.


-- ===== SECTION: 6 — Resource Server layer =====

-- Resource Server: first-class, tenant-owned, peer to Client. Section 7.6.
CREATE TABLE resource_servers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    slug                VARCHAR(63) NOT NULL,              -- used in scope prefixes
    name                VARCHAR(200) NOT NULL,
    audience_identifier VARCHAR(255) NOT NULL,            -- value placed in the token `aud` claim
    is_auto_created     BOOLEAN NOT NULL DEFAULT FALSE,   -- TRUE when created 1:1 with a Client (Section 7.6)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT uq_resource_servers_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT uq_resource_servers_audience     UNIQUE (audience_identifier)  -- global -> unambiguous `aud`
);
-- tenant_id is the leading column of uq_resource_servers_tenant_slug -> no standalone index.

-- Scope catalog per Resource Server.
CREATE TABLE resource_server_scopes (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_server_id UUID NOT NULL REFERENCES resource_servers(id) ON DELETE CASCADE,
    scope_name         VARCHAR(100) NOT NULL,
    description        TEXT,
    is_default         BOOLEAN NOT NULL DEFAULT FALSE,    -- auto-granted when a client accesses this RS
    requires_consent   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_resource_server_scopes UNIQUE (resource_server_id, scope_name)
);
COMMENT ON COLUMN resource_server_scopes.scope_name IS
    'Stored BARE (e.g. "read"), not fully qualified. The "{rs_slug}:" prefix is '
    'prepended at token-issuance time. This keeps RS slug renames cheap (no scope '
    'rows to rewrite) and keeps uniqueness scoped to the resource server.';
-- resource_server_id is the leading column of the composite UNIQUE -> no standalone index.

-- Which Resource Servers a Client may request tokens for. Section 7.6.
CREATE TABLE client_authorized_resource_servers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id          VARCHAR(100) NOT NULL REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,  -- SAS PK, not the OAuth2 client_id
    resource_server_id UUID NOT NULL REFERENCES resource_servers(id) ON DELETE CASCADE,
    authorized_scopes  TEXT,                              -- comma-delimited scope names; NULL means "all scopes"
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_client_authorized_resource_servers UNIQUE (client_id, resource_server_id)
);
-- client_id is the leading column of the composite UNIQUE -> no standalone index.
CREATE INDEX idx_client_authorized_resource_servers_rs_id ON client_authorized_resource_servers (resource_server_id);


-- ===== SECTION: 7 — RBAC layer (Section 7.9) =====

-- Tenant-scoped roles.
CREATE TABLE tenant_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(50) NOT NULL,                  -- TENANT_ADMIN, TENANT_MEMBER, BILLING_ADMIN, or custom
    description TEXT,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,        -- auto-assigned to new users in this tenant
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,        -- TRUE for CloseAuth starter roles; FALSE for custom
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT uq_tenant_roles_tenant_name UNIQUE (tenant_id, name)
);
-- tenant_id is the leading column of the composite UNIQUE -> no standalone index.

-- Assigns tenant roles to users. tenant_id denormalized for query clarity + isolation redundancy.
CREATE TABLE user_tenant_roles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    tenant_role_id      UUID NOT NULL REFERENCES tenant_roles(id) ON DELETE CASCADE,
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_user_tenant_roles UNIQUE (user_id, tenant_id, tenant_role_id)
);
-- user_id is the leading column of the composite UNIQUE -> no standalone index.
CREATE INDEX idx_user_tenant_roles_tenant_user         ON user_tenant_roles (tenant_id, user_id);  -- common query pattern
CREATE INDEX idx_user_tenant_roles_tenant_role_id      ON user_tenant_roles (tenant_role_id);

-- RS-scoped roles (refactored from old application_roles; now keyed to resource_server_id).
CREATE TABLE application_roles (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_server_id UUID NOT NULL REFERENCES resource_servers(id) ON DELETE CASCADE,
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,  -- denormalized
    name               VARCHAR(50) NOT NULL,
    description        TEXT,
    is_default         BOOLEAN NOT NULL DEFAULT FALSE,
    is_system          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ,
    CONSTRAINT uq_application_roles_rs_name UNIQUE (resource_server_id, name)
);
-- resource_server_id is the leading column of the composite UNIQUE -> no standalone index.
CREATE INDEX idx_application_roles_tenant_id ON application_roles (tenant_id);

-- A role is a named bundle of scopes. Section 7.9.
CREATE TABLE application_role_scopes (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_role_id      UUID NOT NULL REFERENCES application_roles(id) ON DELETE CASCADE,
    resource_server_scope_id UUID NOT NULL REFERENCES resource_server_scopes(id) ON DELETE CASCADE,
    CONSTRAINT uq_application_role_scopes UNIQUE (application_role_id, resource_server_scope_id)
);
-- application_role_id is the leading column of the composite UNIQUE -> no standalone index.
CREATE INDEX idx_application_role_scopes_rs_scope_id ON application_role_scopes (resource_server_scope_id);

-- Assigns application roles to users, per Resource Server.
CREATE TABLE user_application_roles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_server_id  UUID NOT NULL REFERENCES resource_servers(id) ON DELETE CASCADE,
    application_role_id UUID NOT NULL REFERENCES application_roles(id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,  -- denormalized
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_user_application_roles UNIQUE (user_id, application_role_id)
);
-- user_id is the leading column of the composite UNIQUE -> no standalone index.
CREATE INDEX idx_user_application_roles_rs_user           ON user_application_roles (resource_server_id, user_id);
CREATE INDEX idx_user_application_roles_application_role  ON user_application_roles (application_role_id);
CREATE INDEX idx_user_application_roles_tenant_id         ON user_application_roles (tenant_id);


-- ===== SECTION: 8 — OAuth2 authorization records (SAS standard tables + tenant column) =====

-- SAS's standard token-storage table. SAS-native columns/types kept precisely
-- (naive `timestamp`, `text` for the blob-equivalent value/metadata columns).
-- Includes device-code and user-code columns (present in the SAS 1.5 family).
CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100) NOT NULL,
    registered_client_id          VARCHAR(100) NOT NULL,
    principal_name                VARCHAR(200) NOT NULL,
    authorization_grant_type      VARCHAR(100) NOT NULL,
    authorized_scopes             VARCHAR(1000) DEFAULT NULL,
    attributes                    TEXT DEFAULT NULL,
    state                         VARCHAR(500) DEFAULT NULL,
    authorization_code_value      TEXT DEFAULT NULL,
    authorization_code_issued_at  TIMESTAMP DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP DEFAULT NULL,
    authorization_code_metadata   TEXT DEFAULT NULL,
    access_token_value            TEXT DEFAULT NULL,
    access_token_issued_at        TIMESTAMP DEFAULT NULL,
    access_token_expires_at       TIMESTAMP DEFAULT NULL,
    access_token_metadata         TEXT DEFAULT NULL,
    access_token_type             VARCHAR(100) DEFAULT NULL,
    access_token_scopes           VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value           TEXT DEFAULT NULL,
    oidc_id_token_issued_at       TIMESTAMP DEFAULT NULL,
    oidc_id_token_expires_at      TIMESTAMP DEFAULT NULL,
    oidc_id_token_metadata        TEXT DEFAULT NULL,
    refresh_token_value           TEXT DEFAULT NULL,
    refresh_token_issued_at       TIMESTAMP DEFAULT NULL,
    refresh_token_expires_at      TIMESTAMP DEFAULT NULL,
    refresh_token_metadata        TEXT DEFAULT NULL,
    user_code_value               TEXT DEFAULT NULL,
    user_code_issued_at           TIMESTAMP DEFAULT NULL,
    user_code_expires_at          TIMESTAMP DEFAULT NULL,
    user_code_metadata            TEXT DEFAULT NULL,
    device_code_value             TEXT DEFAULT NULL,
    device_code_issued_at         TIMESTAMP DEFAULT NULL,
    device_code_expires_at        TIMESTAMP DEFAULT NULL,
    device_code_metadata          TEXT DEFAULT NULL,
    -- CloseAuth addition:
    tenant_id                     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    PRIMARY KEY (id)
);
CREATE INDEX idx_oauth2_authorization_tenant_id            ON oauth2_authorization (tenant_id);
CREATE INDEX idx_oauth2_authorization_registered_client_id ON oauth2_authorization (registered_client_id);

-- SAS's standard consent table (composite PK). Extended with tenant_id.
CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name       VARCHAR(200) NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,
    -- CloseAuth addition:
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    PRIMARY KEY (registered_client_id, principal_name)
);
CREATE INDEX idx_oauth2_authorization_consent_tenant_id ON oauth2_authorization_consent (tenant_id);


-- ===== SECTION: 9 — Token layer (Section 7.3) =====

-- Server-side refresh token store with rotation + replay detection.
-- session_id FK -> auth_server_sessions is added in SECTION 14 (forward reference;
-- auth_server_sessions is defined in SECTION 10).
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash      VARCHAR(255) NOT NULL UNIQUE,      -- hash of the token; never store plaintext
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_id       VARCHAR(100) NOT NULL REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    family_id       UUID NOT NULL,                     -- rotation family; replay revokes the whole family
    parent_token_id UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,  -- token this was rotated from
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
    scopes          TEXT,                              -- space-delimited granted scopes
    session_id      UUID,                              -- Auth Server session; FK added in SECTION 14
    ip_address      INET,
    user_agent      TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX idx_refresh_tokens_family_id       ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_id         ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at      ON refresh_tokens (expires_at);   -- cleanup jobs
CREATE INDEX idx_refresh_tokens_status          ON refresh_tokens (status);
CREATE INDEX idx_refresh_tokens_tenant_id       ON refresh_tokens (tenant_id);
CREATE INDEX idx_refresh_tokens_client_id       ON refresh_tokens (client_id);
CREATE INDEX idx_refresh_tokens_parent_token_id ON refresh_tokens (parent_token_id);
CREATE INDEX idx_refresh_tokens_session_id      ON refresh_tokens (session_id);


-- ===== SECTION: 10 — Session layer (Section 7.5) =====

-- Durable ledger of Auth Server sessions. Redis is authoritative for hot-path
-- session lookups; this table is authoritative for "show me all sessions ever"
-- (cross-device listing, observability, audit joins) and outlives Redis TTLs.
CREATE TABLE auth_server_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    session_key         VARCHAR(255) NOT NULL UNIQUE,  -- opaque session id stored in the cookie
    ip_address          INET,
    user_agent          TEXT,
    remember_me         BOOLEAN NOT NULL DEFAULT FALSE,
    idle_expires_at     TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_accessed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ                     -- set when logged out or invalidated
);
COMMENT ON TABLE auth_server_sessions IS
    'Durable ledger of Auth Server sessions. Redis (Spring Session) is the '
    'authoritative hot store for session lookups; this table is the durable '
    'record for cross-device listing, observability, and audit joins, and it '
    'outlives Redis TTLs. Not consulted on the hot authentication path.';
CREATE INDEX idx_auth_server_sessions_user_tenant     ON auth_server_sessions (user_id, tenant_id);  -- "list my sessions"
CREATE INDEX idx_auth_server_sessions_tenant_id       ON auth_server_sessions (tenant_id);
CREATE INDEX idx_auth_server_sessions_absolute_expiry ON auth_server_sessions (absolute_expires_at);  -- cleanup


-- ===== SECTION: 11 — Agent layer (Section 7.10 — schema only, empty in MVP) =====

-- First-class AI agent principals.
CREATE TABLE agents (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                 VARCHAR(200) NOT NULL,
    agent_type           VARCHAR(50) NOT NULL
        CHECK (agent_type IN ('MCP_SERVER', 'EMBEDDED_AGENT', 'WORKFLOW_RUNNER')),
    agent_owner_user_id  UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,  -- responsible party
    represented_user_id  UUID REFERENCES users(id) ON DELETE CASCADE,            -- who it acts on behalf of
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ,
    CONSTRAINT uq_agents_tenant_name UNIQUE (tenant_id, name)
);
-- tenant_id is the leading column of uq_agents_tenant_name -> no standalone index.
CREATE INDEX idx_agents_agent_owner_user_id ON agents (agent_owner_user_id);
CREATE INDEX idx_agents_represented_user_id ON agents (represented_user_id);

-- User consent authorizing an agent to hold scopes against a Resource Server.
CREATE TABLE agent_consent_grants (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id           UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    granting_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_server_id UUID NOT NULL REFERENCES resource_servers(id) ON DELETE CASCADE,
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    granted_scopes     TEXT NOT NULL,                  -- space-delimited scope names
    granted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at         TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ                      -- optional consent expiry
);
CREATE INDEX idx_agent_consent_grants_agent_user      ON agent_consent_grants (agent_id, granting_user_id);
CREATE INDEX idx_agent_consent_grants_granting_user   ON agent_consent_grants (granting_user_id);
CREATE INDEX idx_agent_consent_grants_resource_server ON agent_consent_grants (resource_server_id);
CREATE INDEX idx_agent_consent_grants_tenant_id       ON agent_consent_grants (tenant_id);


-- ===== SECTION: 12 — Audit layer (Section 7.11) =====

-- Canonical audit log. event_type has NO CHECK constraint: the taxonomy is
-- enforced at the application layer so new event types don't require a migration.
CREATE TABLE audit_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type         VARCHAR(100) NOT NULL,          -- taxonomy value, app-enforced (e.g. USER_LOGIN_SUCCESS)
    tenant_id          UUID REFERENCES tenants(id) ON DELETE RESTRICT,               -- null for platform-level events
    subject_user_id    UUID REFERENCES users(id) ON DELETE RESTRICT,                 -- on whose behalf
    actor_user_id      UUID REFERENCES users(id) ON DELETE RESTRICT,                 -- who performed it (=subject if direct)
    actor_client_id    VARCHAR(100) REFERENCES oauth2_registered_client(id) ON DELETE RESTRICT,  -- M2M actor
    actor_agent_id     UUID REFERENCES agents(id) ON DELETE RESTRICT,                -- agent actor (Phase 4)
    granted_consent_id UUID REFERENCES agent_consent_grants(id) ON DELETE RESTRICT,  -- consent that authorized the agent
    resource_server_id UUID REFERENCES resource_servers(id) ON DELETE RESTRICT,
    ip_address         INET,
    user_agent         TEXT,
    event_data         JSONB NOT NULL,                 -- typed per event_type; app-enforced
    outcome            VARCHAR(20) NOT NULL
        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'ERROR')),
    error_code         VARCHAR(50),                    -- set when outcome != SUCCESS
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Primary query patterns:
CREATE INDEX idx_audit_events_tenant_created ON audit_events (tenant_id, created_at DESC);
CREATE INDEX idx_audit_events_type_created   ON audit_events (event_type, created_at DESC);
CREATE INDEX idx_audit_events_subject_user   ON audit_events (subject_user_id);  -- customer audit API: events about a user
CREATE INDEX idx_audit_events_actor_agent    ON audit_events (actor_agent_id);   -- Phase 4: actions by an agent
CREATE INDEX idx_audit_events_actor_user     ON audit_events (actor_user_id);
-- Note: indexes on actor_client_id, granted_consent_id, resource_server_id were
-- intentionally NOT created. These forensic FKs are rarely queried by-column and
-- the write cost isn't justified at Phase 1 volume; add back if a query pattern needs them.

-- Transactional outbox draining to audit_events (Section 7.11). tenant_id is
-- pulled from the payload for partitioning and intentionally has no FK.
CREATE TABLE audit_outbox (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_payload JSONB NOT NULL,                      -- full event to write to audit_events
    tenant_id     UUID,                                -- denormalized from payload; no FK by design
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at  TIMESTAMPTZ,                         -- NULL = not yet drained
    attempts      INTEGER NOT NULL DEFAULT 0,
    last_error    TEXT
);
-- Outbox worker's primary query: unprocessed rows, oldest first.
CREATE INDEX idx_audit_outbox_unprocessed ON audit_outbox (created_at) WHERE processed_at IS NULL;


-- ===== SECTION: 13 — Read-only role =====

-- General-purpose least-privilege PostgreSQL role for read-only operational access.
-- Intended for: read replicas, debug queries, analytics tooling.
-- NOT intended for: application code, BFF direct access (BFF uses the admin API).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'closeauth_readonly') THEN
        CREATE ROLE closeauth_readonly LOGIN PASSWORD '${closeauthReadonlyPassword}';
    END IF;
END
$$;

GRANT SELECT ON tenants               TO closeauth_readonly;  -- branding queries
GRANT SELECT ON resource_servers      TO closeauth_readonly;  -- scope catalog
GRANT SELECT ON resource_server_scopes TO closeauth_readonly; -- scope catalog
-- Public client metadata only (login-page rendering); NOT secrets/settings.
GRANT SELECT (client_id, client_name, tenant_id, redirect_uris, scopes)
    ON oauth2_registered_client TO closeauth_readonly;
-- Deliberately NO grants on: users, user_identities, refresh_tokens,
-- auth_server_sessions, audit_events, audit_outbox, agent_consent_grants,
-- tenant_idp_connections (and all other sensitive tables).


-- ===== SECTION: 14 — Deferred foreign keys (forward references) =====
-- These FKs point to tables defined in a later section under the required section
-- ordering, so they are added here once all referenced tables exist.

-- user_platform_roles (SECTION 2) -> users (SECTION 4)
ALTER TABLE user_platform_roles
    ADD CONSTRAINT fk_user_platform_roles_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE user_platform_roles
    ADD CONSTRAINT fk_user_platform_roles_granted_by
        FOREIGN KEY (granted_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- refresh_tokens (SECTION 9) -> auth_server_sessions (SECTION 10)
ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_session
        FOREIGN KEY (session_id) REFERENCES auth_server_sessions(id) ON DELETE SET NULL;

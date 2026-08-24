-- =============================================================================
-- CloseAuth — baseline schema
--
-- CloseAuth is pre-production: there is no live database whose history needs
-- preserving, so the full schema lives in ONE Flyway migration (this file)
-- instead of the incremental CREATE/ALTER sequence a shipped product would
-- need. Every table below reflects the CURRENT, final shape of the entity —
-- no follow-up ALTER TABLE anywhere in this file adds a column an app-layer
-- class already expects. Git history has the evolutionary story if it's ever
-- needed; the database itself only needs the destination.
--
-- Conventions:
--   * New PKs: UUID DEFAULT gen_random_uuid().
--   * All timestamps: TIMESTAMPTZ (no naive timestamps).
--     EXCEPTION: Spring Authorization Server (SAS) standard columns keep SAS's
--     native types (incl. naive `timestamp`) so the JDBC/JPA mappers match.
--   * Enum-like columns: VARCHAR + inline CHECK. No native ENUM types.
--   * Every tenant-owned table carries tenant_id -> tenants(id).
--   * Every FK column is indexed unless it is already the leading column of a
--     PK or composite UNIQUE (that index serves the prefix).
--   * Every FK has an explicit ON DELETE action.
--
-- Forward references (a table referencing one defined later, given the
-- required section ordering) are resolved in the final section via
-- ALTER TABLE — see that section's header for why those two survive as
-- ALTERs rather than being reordered away.
-- =============================================================================


-- ===== SECTION: 1 — Extensions and setup =====

-- pgcrypto provides gen_random_uuid(). On PG13+ it is in pg_catalog, but the
-- extension declaration is defensive and harmless on older/managed setups.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";


-- ===== SECTION: 2 — Platform layer =====

-- Platform-level roles (CloseAuth staff only).
CREATE TABLE platform_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) NOT NULL UNIQUE,          -- e.g. PLATFORM_ADMIN, PLATFORM_SUPPORT
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Assigns platform roles to users. VESTIGIAL since platform_admins/platform_admin_roles
-- below became the real home for platform-role assignment: platform admins are a
-- separate entity from tenant `users` (keeps users.tenant_id NOT NULL, the load-bearing
-- isolation invariant, and makes "a platform admin can never be confused for a tenant
-- user" a TYPE guarantee). Kept (not dropped) because PrincipalAuthorizationService reads
-- it harmlessly-empty for tenant users; user_id / granted_by_user_id FKs -> users are
-- added in the final section (users is defined in SECTION 4; this is a forward reference).
CREATE TABLE user_platform_roles (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL,
    platform_role_id   UUID NOT NULL REFERENCES platform_roles(id) ON DELETE RESTRICT,
    granted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    granted_by_user_id UUID,                          -- who granted this role; FK added in the final section
    CONSTRAINT uq_user_platform_roles UNIQUE (user_id, platform_role_id)
);
CREATE INDEX idx_user_platform_roles_platform_role_id   ON user_platform_roles (platform_role_id);

-- Platform admins are CloseAuth STAFF who operate ACROSS all tenants. They are a
-- SEPARATE entity from tenant `users` — NOT users in a system tenant, NOT a nullable
-- users.tenant_id. Different principal, different auth path, different authorization tier.
--
-- Credentials live ON the entity (not the user_identities multi-identity abstraction):
-- platform admins are a small, password-only staff set. Hashed via the same
-- DelegatingPasswordEncoder/bcrypt as tenant users.
CREATE TABLE platform_admins (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- GLOBALLY unique (no tenant scoping) — the deliberate difference from users' per-tenant
    -- (tenant_id, email) uniqueness. Platform admins are not tenant-scoped.
    email         VARCHAR(255) NOT NULL UNIQUE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    first_name    VARCHAR(100),
    last_name     VARCHAR(100),
    password_hash VARCHAR(255) NOT NULL,          -- {id}-prefixed encoded hash (DelegatingPasswordEncoder)
    password_algo VARCHAR(50)  NOT NULL,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ
);
COMMENT ON TABLE platform_admins IS
    'CloseAuth staff who operate across all tenants. A separate entity from tenant '
    'users (keeps users.tenant_id NOT NULL); globally-unique email; password credentials '
    'stored inline (staff are password-only in MVP).';

-- Assigns platform roles to platform admins — the real home for platform-role
-- assignment (see user_platform_roles above for why that table is vestigial).
CREATE TABLE platform_admin_roles (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_admin_id            UUID NOT NULL REFERENCES platform_admins(id) ON DELETE CASCADE,
    platform_role_id             UUID NOT NULL REFERENCES platform_roles(id) ON DELETE RESTRICT,
    granted_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    granted_by_platform_admin_id UUID REFERENCES platform_admins(id) ON DELETE SET NULL,
    CONSTRAINT uq_platform_admin_roles UNIQUE (platform_admin_id, platform_role_id)
);
-- platform_admin_id is the leading column of the composite UNIQUE -> no standalone index needed.
CREATE INDEX idx_platform_admin_roles_platform_role_id ON platform_admin_roles (platform_role_id);

-- Deliberately NO grants to closeauth_readonly on platform_admins/platform_admin_roles:
-- platform_admins holds staff credentials.


-- ===== SECTION: 3 — Tenant layer =====

-- The first-class Tenant entity.
CREATE TABLE tenants (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- immutable; carried by tokens & FKs
    slug       VARCHAR(63) NOT NULL UNIQUE,                 -- mutable, DNS-safe, ten_-prefixed, used in URLs/display
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING'
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ                                  -- set on transition to DELETED; hard-purge job uses this
);
CREATE INDEX idx_tenants_status ON tenants (status);

-- Declares a tenant's self-registration mode (the strategy the registration flow
-- selects). 1:1 with tenant (UNIQUE tenant_id); structured to accrue policy later
-- (allowed email domains, default role on registration, auto-approve conditions,
-- enumeration-safe-registration) without a rewrite. A default row (EMAIL_VERIFIED)
-- is created for every tenant at provisioning time via the TenantProvisioningCallback seam.
CREATE TABLE tenant_registration_config (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,  -- 1:1 with tenant
    mode        VARCHAR(20) NOT NULL DEFAULT 'EMAIL_VERIFIED'
        CHECK (mode IN ('OPEN', 'EMAIL_VERIFIED', 'ADMIN_APPROVED', 'INVITE_ONLY')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);
COMMENT ON TABLE tenant_registration_config IS
    'Per-tenant self-registration mode. 1:1 with tenant; a default row is created at '
    'tenant provisioning. Structured to grow into a fuller registration policy '
    '(email domains, default role, auto-approve) later.';
-- tenant_id already has a UNIQUE index (the resolve-by-tenant lookup path).

-- A tenant owns its branding, shared across ALL its clients. The hosted
-- login/consent/registration pages resolve branding by client_id -> tenant_id ->
-- tenant_branding; the client is only the lookup key that identifies WHICH tenant's
-- brand to render. 1:1 with tenant, like tenant_registration_config.
--
-- SECURITY — structured fields ONLY, deliberately NO raw custom CSS: tenant-supplied
-- CSS on a CloseAuth-hosted page is an injection vector (exfiltration via
-- background-image URLs, phishing via UI obfuscation). We store only validated
-- structured fields (hex colors, a URL, names); the app layer validates on write and
-- the UI layer is responsible for safe injection (CSS-escaping, CSP) — a two-layer
-- defense. Custom CSS, if ever added, would need sanitization/sandboxing.
CREATE TABLE tenant_branding (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,  -- 1:1 with tenant
    logo_url         VARCHAR(500),                 -- nullable; https recommended (validated in the service)
    primary_color    VARCHAR(7),                   -- hex #RRGGBB; nullable -> platform default
    background_color VARCHAR(7),                   -- hex #RRGGBB; nullable -> platform default
    accent_color     VARCHAR(7),                   -- hex #RRGGBB; nullable -> platform default
    company_name     VARCHAR(200),                 -- nullable; e.g. "Acme" -> "Sign in to Acme"
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ
);
COMMENT ON TABLE tenant_branding IS
    'Per-tenant hosted-page branding. 1:1 with tenant. Structured, validated fields '
    'only (NO raw custom CSS — injection risk). Resolved for the hosted pages by '
    'client_id -> tenant -> this row, with platform defaults filling null fields.';
-- tenant_id already has a UNIQUE index (the resolve-by-tenant lookup path).
-- Non-sensitive by nature (rendered on public login pages), so no readonly-grant concern.


-- ===== SECTION: 4 — Identity layer =====
-- Ordered users -> tenant_idp_connections -> user_identities so that
-- user_identities.idp_connection_id can reference tenant_idp_connections inline.

-- Users, decoupled from credentials (NO password_hash here).
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
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email)  -- per-tenant email uniqueness
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

-- Links a user to a credential source.
CREATE TABLE user_identities (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id                  UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,  -- denormalized for isolation checks
    idp_type                   VARCHAR(50) NOT NULL
        CHECK (idp_type IN ('LOCAL_PASSWORD', 'SOCIAL_GOOGLE', 'SOCIAL_GITHUB', 'OIDC_FEDERATED', 'SAML', 'AGENT_KEY')),
    idp_subject                VARCHAR(500),                    -- upstream identifier; null for LOCAL_PASSWORD/AGENT_KEY
    idp_connection_id          UUID REFERENCES tenant_idp_connections(id) ON DELETE RESTRICT,  -- only for federated identities
    password_hash              VARCHAR(255),                    -- only when idp_type = LOCAL_PASSWORD
    password_algo              VARCHAR(50),                     -- e.g. bcrypt, argon2id
    -- Tenant-admin onboarding credential lifecycle: forced-rotation gate + the temp
    -- credential's own expiry. Both live on the IDENTITY, not the user: they are
    -- properties of a LOCAL_PASSWORD credential, and `users` deliberately holds no
    -- credential state.
    must_change_password       BOOLEAN NOT NULL DEFAULT FALSE,  -- true = authenticates but must rotate before a session issues
    temp_credential_expires_at TIMESTAMPTZ,                     -- hard expiry of a system-generated temp credential; NULL for user-chosen passwords
    metadata                   JSONB,                           -- provider-specific attributes
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ,
    CONSTRAINT uq_user_identities_user_idp UNIQUE (user_id, idp_type, idp_subject)
);
COMMENT ON COLUMN user_identities.must_change_password IS
    'Forced-rotation gate: when true this credential authenticates but must not yield a '
    'session until rotated. Set for system-generated temp credentials only.';
COMMENT ON COLUMN user_identities.temp_credential_expires_at IS
    'Hard expiry of a system-generated temp credential (7 days). NULL for every '
    'user-chosen password — those never expire on this axis.';
-- user_id is the leading column of the composite UNIQUE above -> no standalone index.
CREATE INDEX idx_user_identities_tenant_id         ON user_identities (tenant_id);
CREATE INDEX idx_user_identities_idp_type          ON user_identities (idp_type);
CREATE INDEX idx_user_identities_idp_connection_id ON user_identities (idp_connection_id);


-- ===== SECTION: 5 — Client layer (OAuth2 registered clients + tenant ownership) =====

-- Spring Authorization Server's standard table, extended with tenant ownership.
-- SAS-native columns keep SAS's types (incl. naive `timestamp`) to match the
-- JDBC/JPA client mapper precisely. There is NO separate client_ownership table;
-- ownership is expressed via tenant_id + tenant admin roles.
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

-- Resource Server: first-class, tenant-owned, peer to Client.
CREATE TABLE resource_servers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    slug                VARCHAR(63) NOT NULL,              -- used in scope prefixes
    name                VARCHAR(200) NOT NULL,
    audience_identifier VARCHAR(255) NOT NULL,            -- value placed in the token `aud` claim
    is_auto_created     BOOLEAN NOT NULL DEFAULT FALSE,   -- TRUE when created 1:1 with a Client
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

-- Which Resource Servers a Client may request tokens for.
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


-- ===== SECTION: 7 — RBAC layer =====

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

-- RS-scoped roles, keyed to resource_server_id.
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

-- A role is a named bundle of scopes.
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


-- ===== SECTION: 9 — Token layer =====

-- Server-side refresh token store with rotation + replay detection.
-- session_id FK -> auth_server_sessions is added in the final section (forward
-- reference; auth_server_sessions is defined in SECTION 11).
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
    session_id      UUID,                              -- Auth Server session; FK added in the final section
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


-- ===== SECTION: 10 — One-time tokens =====

-- The single durable, auditable home for every out-of-band single-use secret:
-- email verification, magic-link login, password reset, invites, and tenant-admin
-- onboarding. Chosen over Redis because auditability is a tier-1 platform posture
-- and these tokens are exactly what security investigations join against; Redis's
-- only free feature (TTL eviction) is cheaply replaced by lazy-expire-on-consume +
-- a periodic sweep (idx_one_time_tokens_expiry_sweep).
CREATE TABLE one_time_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- SHA-256 hex of the raw secret. The raw value is NEVER stored; consume looks
    -- the token up by this hash.
    token_hash    VARCHAR(255) NOT NULL UNIQUE,
    purpose       VARCHAR(30)  NOT NULL
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'MAGIC_LINK', 'PASSWORD_RESET', 'INVITE',
                           'TENANT_ADMIN_ONBOARDING')),
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Nullable: an INVITE can be minted before the user row exists.
    user_id       UUID REFERENCES users(id) ON DELETE CASCADE,
    target        VARCHAR(255) NOT NULL,             -- the email the secret was delivered to
    payload       JSONB,                             -- small purpose-specific payload (nullable)
    expires_at    TIMESTAMPTZ NOT NULL,
    used          BOOLEAN NOT NULL DEFAULT FALSE,
    used_at       TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,        -- failed consume attempts (low-entropy numeric-code lockout)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ
);
COMMENT ON TABLE one_time_tokens IS
    'Unified single-use out-of-band secrets (email verification, magic link, '
    'password reset, invite, tenant-admin onboarding). Stores only the SHA-256 hash '
    'of the raw token; single-use is enforced by an atomic conditional UPDATE on '
    'consume. Durable + auditable; expiry is enforced on consume and reclaimed by a sweep.';
-- token_hash already has a UNIQUE index (the consume lookup path).
-- Supports invalidateForTarget (new reset token invalidates prior unused ones).
CREATE INDEX idx_one_time_tokens_purpose_tenant_target ON one_time_tokens (purpose, tenant_id, target);
-- Partial index for the expiry sweep: only unexpired-unused rows matter to reclaim.
CREATE INDEX idx_one_time_tokens_expiry_sweep ON one_time_tokens (expires_at) WHERE used = FALSE;

-- Deliberately NO grants to closeauth_readonly: one_time_tokens holds security-
-- sensitive material (same posture as users, refresh_tokens, auth_server_sessions).


-- ===== SECTION: 11 — Session layer =====

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


-- ===== SECTION: 12 — Agent layer (schema only, empty in MVP) =====

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


-- ===== SECTION: 13 — Audit layer =====

-- Canonical audit log. event_type has NO CHECK constraint: the taxonomy is
-- enforced at the application layer so new event types don't require a migration.
CREATE TABLE audit_events (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type               VARCHAR(100) NOT NULL,          -- taxonomy value, app-enforced (e.g. USER_LOGIN_SUCCESS)
    tenant_id                UUID REFERENCES tenants(id) ON DELETE RESTRICT,               -- null for platform-level events
    subject_user_id          UUID REFERENCES users(id) ON DELETE RESTRICT,                 -- on whose behalf
    actor_user_id            UUID REFERENCES users(id) ON DELETE RESTRICT,                 -- who performed it (=subject if direct)
    actor_client_id          VARCHAR(100) REFERENCES oauth2_registered_client(id) ON DELETE RESTRICT,  -- M2M actor
    actor_agent_id           UUID REFERENCES agents(id) ON DELETE RESTRICT,                -- agent actor (Phase 4)
    -- Platform-admin actor: set when the actor is CloseAuth staff acting across tenants;
    -- mutually exclusive with actor_user_id (a tenant user) — a distinct principal type.
    -- No index: like actor_client_id/granted_consent_id below, this forensic FK is rarely
    -- queried by-column and the write cost isn't justified at Phase 1 volume.
    actor_platform_admin_id  UUID REFERENCES platform_admins(id) ON DELETE RESTRICT,
    granted_consent_id       UUID REFERENCES agent_consent_grants(id) ON DELETE RESTRICT,  -- consent that authorized the agent
    resource_server_id       UUID REFERENCES resource_servers(id) ON DELETE RESTRICT,
    ip_address               INET,
    user_agent               TEXT,
    event_data               JSONB NOT NULL,                 -- typed per event_type; app-enforced
    outcome                  VARCHAR(20) NOT NULL
        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'ERROR')),
    error_code               VARCHAR(50),                    -- set when outcome != SUCCESS
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Primary query patterns:
CREATE INDEX idx_audit_events_tenant_created ON audit_events (tenant_id, created_at DESC);
CREATE INDEX idx_audit_events_type_created   ON audit_events (event_type, created_at DESC);
CREATE INDEX idx_audit_events_subject_user   ON audit_events (subject_user_id);  -- customer audit API: events about a user
CREATE INDEX idx_audit_events_actor_agent    ON audit_events (actor_agent_id);   -- Phase 4: actions by an agent
CREATE INDEX idx_audit_events_actor_user     ON audit_events (actor_user_id);
-- Note: indexes on actor_client_id, granted_consent_id, resource_server_id, and
-- actor_platform_admin_id were intentionally NOT created. These forensic FKs are
-- rarely queried by-column and the write cost isn't justified at Phase 1 volume;
-- add back if a query pattern needs them.

-- Transactional outbox draining to audit_events. tenant_id is pulled from the
-- payload for partitioning and intentionally has no FK.
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


-- ===== SECTION: 14 — Read-only role =====

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
-- tenant_idp_connections, one_time_tokens, platform_admins, platform_admin_roles
-- (and all other sensitive tables).


-- ===== SECTION: 15 — Deferred foreign keys (forward references) =====
-- Both of these point to a table defined in a LATER section under the section
-- ordering above (grouped by architectural layer, not by dependency order), so
-- they are added here once all referenced tables exist, exactly as the original
-- staged migrations did. Reordering the sections to avoid this entirely was
-- considered and rejected: the layer grouping above is the more useful read for
-- anyone auditing the schema, and two ALTERs is a small price for it.

-- user_platform_roles (SECTION 2) -> users (SECTION 4)
ALTER TABLE user_platform_roles
    ADD CONSTRAINT fk_user_platform_roles_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE user_platform_roles
    ADD CONSTRAINT fk_user_platform_roles_granted_by
        FOREIGN KEY (granted_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- refresh_tokens (SECTION 9) -> auth_server_sessions (SECTION 11)
ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_session
        FOREIGN KEY (session_id) REFERENCES auth_server_sessions(id) ON DELETE SET NULL;

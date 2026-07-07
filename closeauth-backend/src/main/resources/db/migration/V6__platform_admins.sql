-- =============================================================================
-- CloseAuth — Stage 7a: platform admins (§7.8 / §7.9)
--
-- Platform admins are CloseAuth STAFF who operate ACROSS all tenants. They are a
-- SEPARATE entity from tenant `users` (NOT users in a system tenant, NOT a nullable
-- users.tenant_id) — this keeps users.tenant_id NOT NULL (the load-bearing isolation
-- invariant) and makes "a platform admin can never be confused for a tenant user" a
-- TYPE guarantee. Different principal, different auth path, different authorization tier.
--
-- Credentials: option B (creds ON the entity) — platform admins are a small, password-
-- only staff set in MVP; the full user_identities multi-identity abstraction is overkill.
-- Hashed via the same DelegatingPasswordEncoder/bcrypt as tenant users (reused, not
-- duplicated). Migrate to a parallel platform_admin_identities table if staff federation
-- is ever wanted.
--
-- platform_admin_roles replaces user_platform_roles for platform admins: user_platform_roles
-- (V1) FKs user_id -> users, which is WRONG for the separate platform_admins entity. Platform
-- roles are only ever held by platform admins, so user_platform_roles is now VESTIGIAL — kept
-- (not dropped) to avoid touching V1/other code that reads it (PrincipalAuthorizationService
-- reads it harmlessly-empty for tenant users); its removal is a later cleanup. Documented in
-- STAGE_7A_REPORT.md.
--
-- Conventions follow V1: UUID PK, TIMESTAMPTZ, VARCHAR+CHECK, explicit ON DELETE. V1-V5 untouched.
-- =============================================================================

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
    'CloseAuth staff who operate across all tenants (§7.8). A separate entity from tenant '
    'users (keeps users.tenant_id NOT NULL); globally-unique email; password credentials '
    'stored inline (staff are password-only in MVP).';

-- Assigns platform roles (platform_roles, V1/V2) to platform admins.
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

-- Deliberately NO grants to closeauth_readonly: platform_admins holds staff credentials.

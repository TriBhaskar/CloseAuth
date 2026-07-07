-- =============================================================================
-- CloseAuth — Stage 6b-i: per-tenant registration configuration (§9.2)
--
-- Declares a tenant's self-registration mode (the strategy the registration flow
-- selects). 1:1 with tenant for now (UNIQUE tenant_id); structured to accrue policy
-- later (allowed email domains, require-verification-independent-of-mode, default
-- role on registration, auto-approve conditions, and an enumeration-safe-registration
-- flag [whether POST /register reveals 'email already registered' or returns a uniform
-- response — a per-tenant choice, not a platform-wide one]) without a rewrite, and could
-- become per-client in a later phase. A default row (EMAIL_VERIFIED) is created for every
-- tenant at provisioning time via the TenantProvisioningCallback seam; tenants that
-- predate this table fall back to the platform default in code.
--
-- Conventions follow V1: UUID PK, TIMESTAMPTZ, VARCHAR+CHECK (no native enum),
-- tenant_id -> tenants(id), explicit ON DELETE. V1 is untouched.
-- =============================================================================

CREATE TABLE tenant_registration_config (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,  -- 1:1 with tenant
    mode        VARCHAR(20) NOT NULL DEFAULT 'EMAIL_VERIFIED'
        CHECK (mode IN ('OPEN', 'EMAIL_VERIFIED', 'ADMIN_APPROVED', 'INVITE_ONLY')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);
COMMENT ON TABLE tenant_registration_config IS
    'Per-tenant self-registration mode (§9.2). 1:1 with tenant; a default row is '
    'created at tenant provisioning. Structured to grow into a fuller registration '
    'policy (email domains, default role, auto-approve) later.';

-- tenant_id already has a UNIQUE index (the resolve-by-tenant lookup path).

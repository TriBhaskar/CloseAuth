-- =============================================================================
-- CloseAuth — Stage 6b-ii: per-tenant branding (§7.1)
--
-- A tenant owns its branding, shared across ALL its clients (a change from the old
-- per-client_id client_themes model). The hosted login/consent/registration pages
-- resolve branding by client_id -> tenant_id -> tenant_branding; the client is only
-- the lookup key that identifies WHICH tenant's brand to render.
--
-- 1:1 with tenant (like tenant_registration_config). A default row is created at
-- tenant provisioning via the TenantProvisioningCallback, so every tenant has one.
--
-- SECURITY — structured fields ONLY, deliberately NO raw custom CSS: tenant-supplied
-- CSS on a CloseAuth-hosted page is an injection vector (exfiltration via
-- background-image URLs, phishing via UI obfuscation). We store only validated
-- structured fields (hex colors, a URL, names); the app layer validates on write and
-- the UI layer is responsible for safe injection (CSS-escaping, CSP) — a two-layer
-- defense. Custom CSS, if ever added, would need sanitization/sandboxing.
--
-- Conventions follow V1: UUID PK, TIMESTAMPTZ, tenant_id -> tenants(id), explicit
-- ON DELETE. V1-V4 are untouched.
-- =============================================================================

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
    'Per-tenant hosted-page branding (§7.1). 1:1 with tenant. Structured, validated '
    'fields only (NO raw custom CSS — injection risk). Resolved for the hosted pages '
    'by client_id -> tenant -> this row, with platform defaults filling null fields.';

-- tenant_id already has a UNIQUE index (the resolve-by-tenant lookup path).
-- Non-sensitive by nature (rendered on public login pages), so no readonly-grant concern.

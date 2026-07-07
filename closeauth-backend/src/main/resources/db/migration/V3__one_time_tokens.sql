-- =============================================================================
-- CloseAuth — Stage 6b-i: unified one-time-token store (§13.4 / §7.4)
--
-- The single durable, auditable home for every out-of-band single-use secret:
-- email verification, magic-link login, password reset, and invites. Chosen over
-- Redis because auditability is a tier-1 platform posture (§7.11) and these tokens
-- are exactly what security investigations join against; Redis's only free feature
-- (TTL eviction) is cheaply replaced by lazy-expire-on-consume + a periodic sweep
-- (idx_one_time_tokens_expiry_sweep). Same two-store discipline as Stage 5
-- (Redis = hot/ephemeral, Postgres = durable ledger); OTTs are ledger-like.
--
-- Conventions follow V1: UUID PK, TIMESTAMPTZ, VARCHAR+CHECK (no native enum),
-- tenant_id -> tenants(id), every FK indexed + explicit ON DELETE. V1 is untouched.
-- =============================================================================

CREATE TABLE one_time_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- SHA-256 hex of the raw secret. The raw value is NEVER stored (fixing the old
    -- plaintext-OTP behavior); consume looks the token up by this hash.
    token_hash    VARCHAR(255) NOT NULL UNIQUE,
    purpose       VARCHAR(30)  NOT NULL
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'MAGIC_LINK', 'PASSWORD_RESET', 'INVITE')),
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
    'password reset, invite). Stores only the SHA-256 hash of the raw token; '
    'single-use is enforced by an atomic conditional UPDATE on consume. Durable + '
    'auditable (§7.11); expiry is enforced on consume and reclaimed by a sweep.';

-- token_hash already has a UNIQUE index (the consume lookup path).
-- Supports invalidateForTarget (new reset token invalidates prior unused ones).
CREATE INDEX idx_one_time_tokens_purpose_tenant_target ON one_time_tokens (purpose, tenant_id, target);
-- Partial index for the expiry sweep: only unexpired-unused rows matter to reclaim.
CREATE INDEX idx_one_time_tokens_expiry_sweep ON one_time_tokens (expires_at) WHERE used = FALSE;

-- Deliberately NO grants to closeauth_readonly: one_time_tokens holds security-
-- sensitive material (same posture as users, refresh_tokens, auth_server_sessions).

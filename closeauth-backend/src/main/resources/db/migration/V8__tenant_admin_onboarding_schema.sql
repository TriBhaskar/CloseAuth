-- =============================================================================
-- CloseAuth — Phase 1 (inert schema): tenant-admin onboarding credential lifecycle
--
-- SCHEMA ONLY. Nothing reads these columns or this purpose value yet; the behavior
-- that uses them lands in a later phase. Landing them first keeps the eventual
-- behavioral change reviewable in isolation and lets `ddl-auto: validate` prove the
-- entity/schema match independently of any flow change. See
-- docs/TENANT_ONBOARDING_UI_ANALYSIS.md §2.2 (rotation gate), §2.2.1 (dedicated OTT
-- purpose), §2.3 (7-day TTL).
--
-- (1) user_identities gains the forced-rotation gate + the temp credential's own
--     expiry. Both live on the IDENTITY, not the user: they are properties of a
--     LOCAL_PASSWORD credential, and `users` deliberately holds no credential state
--     (V1's identity/credential split). must_change_password is NOT NULL DEFAULT
--     false so every existing row is unambiguously "no rotation pending"; the
--     expiry is nullable because only system-generated temp credentials ever have
--     one — an ordinary, user-chosen password never expires on this axis.
--
-- (2) one_time_tokens.purpose widens to admit TENANT_ADMIN_ONBOARDING. V3 declared
--     this CHECK inline in CREATE TABLE, so Postgres auto-named it; the name below
--     (one_time_tokens_purpose_check) was CONFIRMED against the running database
--     (`SELECT conname FROM pg_constraint WHERE conrelid = 'one_time_tokens'::regclass
--     AND contype = 'c'`) before writing this migration — DO NOT change this to
--     DROP CONSTRAINT IF EXISTS: if the real name were ever different, IF EXISTS
--     would silently no-op, leaving the OLD, more restrictive constraint in place
--     and rejecting the new purpose at runtime with no migration-time error to catch
--     it. A bare DROP fails loudly instead if the name doesn't match, which is the
--     correct failure mode here. Re-adding it named (rather than inline) also
--     normalizes the constraint for whichever migration touches it next.
--
--     TENANT_ADMIN_ONBOARDING is 23 characters — fits the existing purpose
--     VARCHAR(30) column with room to spare.
--
-- Conventions follow V1/V3/V7: nullable where possible, VARCHAR+CHECK (no native
-- enums), TIMESTAMPTZ. V1-V7 untouched.
-- =============================================================================

ALTER TABLE user_identities
    ADD COLUMN must_change_password       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN temp_credential_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN user_identities.must_change_password IS
    'Forced-rotation gate: when true this credential authenticates but must not yield a '
    'session until rotated (§2.2). Set for system-generated temp credentials only.';
COMMENT ON COLUMN user_identities.temp_credential_expires_at IS
    'Hard expiry of a system-generated temp credential (§2.3, 7 days). NULL for every '
    'user-chosen password — those never expire on this axis.';

ALTER TABLE one_time_tokens
    DROP CONSTRAINT one_time_tokens_purpose_check;
ALTER TABLE one_time_tokens
    ADD CONSTRAINT one_time_tokens_purpose_check
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'MAGIC_LINK', 'PASSWORD_RESET',
                           'INVITE', 'TENANT_ADMIN_ONBOARDING'));

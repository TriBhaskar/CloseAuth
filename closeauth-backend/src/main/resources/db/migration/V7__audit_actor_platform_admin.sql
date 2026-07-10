-- =============================================================================
-- CloseAuth — Stage 8: audit actor for platform admins (§7.11)
--
-- Post-7a, platform admins are a SEPARATE entity from tenant `users` (V6). An audit
-- event whose ACTOR is a platform admin therefore cannot be represented by
-- audit_events.actor_user_id (that FK -> users, the wrong table). Reusing actor_user_id
-- for a platform-admin UUID would conflate the two principals the exact way 7a's whole
-- design keeps them type-distinct. So this migration adds a dedicated, nullable
-- actor_platform_admin_id, consistent with keeping platform admins type-distinct
-- everywhere else in the system. See STAGE_8_REPORT.md (Actor representation decision).
--
-- ON DELETE RESTRICT mirrors every other audit_events actor/subject FK (Stage 1 C5):
-- an audit trail must pin the principals it references (they cannot be hard-deleted while
-- audit rows about them exist). No index: like actor_client_id/granted_consent_id (V1),
-- this forensic FK is rarely queried by-column and the write cost isn't justified at
-- Phase 1 volume — add one back if a query pattern needs it.
--
-- Conventions follow V1/V6: nullable UUID, explicit ON DELETE. V1-V6 untouched.
-- =============================================================================

ALTER TABLE audit_events
    ADD COLUMN actor_platform_admin_id UUID REFERENCES platform_admins(id) ON DELETE RESTRICT;

COMMENT ON COLUMN audit_events.actor_platform_admin_id IS
    'Platform-admin actor (§7.8/§7.11). Set when the actor is CloseAuth staff acting across '
    'tenants; mutually exclusive with actor_user_id (a tenant user) — a distinct principal type.';

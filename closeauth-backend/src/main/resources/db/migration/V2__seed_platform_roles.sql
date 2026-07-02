-- =============================================================================
-- V2 — Seed platform-role DEFINITIONS (reference data)
--
-- Platform roles are CloseAuth-staff roles (§7.9). These are role DEFINITIONS only —
-- reference data that the RBAC layer's logic depends on, seeded in a migration so it is
-- versioned with the schema and present identically in every environment (including fresh
-- test databases).
--
-- This does NOT create any platform admin USER, and does NOT resolve the
-- "user with no tenant vs. users.tenant_id NOT NULL" question — both are deferred to a
-- Stage 7-area bootstrap step.
-- =============================================================================

-- id defaults to gen_random_uuid(); created_at defaults to NOW(). name is UNIQUE, so the
-- upsert makes this migration safe to (re)apply against a database that already has the rows.
INSERT INTO platform_roles (name, description) VALUES
    ('PLATFORM_ADMIN',   'CloseAuth platform administrator (staff)'),
    ('PLATFORM_SUPPORT', 'CloseAuth platform support (staff)')
ON CONFLICT (name) DO NOTHING;

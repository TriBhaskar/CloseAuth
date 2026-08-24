-- =============================================================================
-- V2 — Seed data (reference data, not application state)
--
-- Everything the app depends on existing as ROWS rather than schema, versioned
-- alongside V1 so it's present identically in every environment (including
-- fresh test databases). Keep this file to reference data only — platform
-- roles today, and the natural home for any future fixed lookup rows. It does
-- NOT create any platform admin USER; user-shaped data comes from the app's
-- own bootstrap/provisioning flows, not a migration.
--
-- Platform roles are CloseAuth-staff role DEFINITIONS only, reference data
-- the RBAC layer's logic depends on.
-- =============================================================================

-- id defaults to gen_random_uuid(); created_at defaults to NOW(). name is UNIQUE, so the
-- upsert makes this migration safe to (re)apply against a database that already has the rows.
INSERT INTO platform_roles (name, description) VALUES
    ('PLATFORM_ADMIN',   'CloseAuth platform administrator (staff)'),
    ('PLATFORM_SUPPORT', 'CloseAuth platform support (staff)')
ON CONFLICT (name) DO NOTHING;

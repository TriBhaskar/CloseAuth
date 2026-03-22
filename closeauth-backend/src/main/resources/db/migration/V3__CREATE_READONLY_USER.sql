-- =========================
-- Create Read-Only User for Theme Tables
-- =========================
-- This user will be used by external services that need to read theme configurations
-- but should not have access to other sensitive data in the database

-- Create the read-only user (change password in production!)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'bff_readonly_user') THEN
        CREATE USER bff_readonly_user WITH PASSWORD 'bff_readonly_password';
    END IF;
END
$$;

-- Revoke all default privileges to ensure clean state
REVOKE ALL PRIVILEGES ON DATABASE closeauth FROM bff_readonly_user;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM bff_readonly_user;

-- Grant minimal connection privileges
GRANT CONNECT ON DATABASE closeauth TO bff_readonly_user;
GRANT USAGE ON SCHEMA public TO bff_readonly_user;

-- Grant SELECT permission ONLY on client_themes and theme_configurations tables
GRANT SELECT ON TABLE client_themes TO bff_readonly_user;
GRANT SELECT ON TABLE theme_configurations TO bff_readonly_user;

-- Grant usage on the sequences (needed for potential future operations, but not write access)
GRANT USAGE ON SEQUENCE client_themes_id_seq TO bff_readonly_user;
GRANT USAGE ON SEQUENCE theme_configurations_id_seq TO bff_readonly_user;

-- Explicitly revoke INSERT, UPDATE, DELETE on these tables (defense in depth)
REVOKE INSERT, UPDATE, DELETE ON TABLE client_themes FROM bff_readonly_user;
REVOKE INSERT, UPDATE, DELETE ON TABLE theme_configurations FROM bff_readonly_user;

-- Prevent this user from accessing any other tables
-- Note: By default, the user won't have access to other tables anyway,
-- but this makes it explicit and documents the intent

COMMENT ON ROLE bff_readonly_user IS 'Read-only user for external services accessing client_themes and theme_configurations tables';


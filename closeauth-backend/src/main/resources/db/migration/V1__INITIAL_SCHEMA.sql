
CREATE TABLE oauth2_registered_client (
    id VARCHAR(100) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    client_id_issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret VARCHAR(200) DEFAULT NULL,
    client_secret_expires_at TIMESTAMP DEFAULT NULL,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(1000) DEFAULT NULL,
    post_logout_redirect_uris VARCHAR(1000) DEFAULT NULL,
    scopes VARCHAR(1000) NOT NULL,
    client_settings VARCHAR(2000) NOT NULL,
    token_settings VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorities VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

CREATE TABLE oauth2_authorization (
    id VARCHAR(100) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes VARCHAR(1000) DEFAULT NULL,
    attributes TEXT DEFAULT NULL,
    state VARCHAR(500) DEFAULT NULL,
    authorization_code_value TEXT DEFAULT NULL,
    authorization_code_issued_at TIMESTAMP DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP DEFAULT NULL,
    authorization_code_metadata TEXT DEFAULT NULL,
    access_token_value TEXT DEFAULT NULL,
    access_token_issued_at TIMESTAMP DEFAULT NULL,
    access_token_expires_at TIMESTAMP DEFAULT NULL,
    access_token_metadata TEXT DEFAULT NULL,
    access_token_type VARCHAR(100) DEFAULT NULL,
    access_token_scopes VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value TEXT DEFAULT NULL,
    oidc_id_token_issued_at TIMESTAMP DEFAULT NULL,
    oidc_id_token_expires_at TIMESTAMP DEFAULT NULL,
    oidc_id_token_metadata TEXT DEFAULT NULL,
    oidc_id_token_claims TEXT DEFAULT NULL,
    refresh_token_value TEXT DEFAULT NULL,
    refresh_token_issued_at TIMESTAMP DEFAULT NULL,
    refresh_token_expires_at TIMESTAMP DEFAULT NULL,
    refresh_token_metadata TEXT DEFAULT NULL,
    user_code_value TEXT DEFAULT NULL,
    user_code_issued_at TIMESTAMP DEFAULT NULL,
    user_code_expires_at TIMESTAMP DEFAULT NULL,
    user_code_metadata TEXT DEFAULT NULL,
    device_code_value TEXT DEFAULT NULL,
    device_code_issued_at TIMESTAMP DEFAULT NULL,
    device_code_expires_at TIMESTAMP DEFAULT NULL,
    device_code_metadata TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);

-- =========================
-- Client Theme Configurations
-- =========================
CREATE TABLE client_themes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id       VARCHAR(100) NOT NULL,
    theme_name      VARCHAR(100) NOT NULL,        -- e.g. 'default', 'dark', 'corporate'
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id, theme_name),
    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE
);

-- =========================
-- Theme Configuration Details
-- =========================
CREATE TABLE theme_configurations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    theme_id        INTEGER NOT NULL,
    config_key      VARCHAR(100) NOT NULL,        -- e.g. 'primary_color', 'logo_url', 'font_family'
    config_value    TEXT NOT NULL,                -- JSON value or simple text
    config_type     VARCHAR(50) DEFAULT 'string', -- string, color, url, json, number
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(theme_id, config_key),
    FOREIGN KEY(theme_id) REFERENCES client_themes(id) ON DELETE CASCADE
);

-- =========================
-- Global Users
-- =========================
CREATE TABLE users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        VARCHAR(100) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    algo            VARCHAR(50) DEFAULT 'bcrypt', -- e.g. bcrypt, argon2
    failed_attempts INTEGER DEFAULT 0,            -- Track failed login attempts
    locked_until    TIMESTAMP,                    -- Account lockout timestamp
    password_changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expired         BOOLEAN DEFAULT FALSE,
    locked          BOOLEAN DEFAULT FALSE,
    credentials_expired BOOLEAN DEFAULT FALSE,
    disabled        BOOLEAN DEFAULT FALSE,
    email           VARCHAR(255) UNIQUE NOT NULL,
    email_verified  BOOLEAN DEFAULT FALSE,
    phone           VARCHAR(20),
    phone_verified  BOOLEAN DEFAULT FALSE,        -- Added phone verification
    first_name      VARCHAR(100),                 -- Added basic profile fields
    last_name       VARCHAR(100),
    status          VARCHAR(20) CHECK(status IN ('PENDING','ACTIVE','SUSPENDED','DELETED')) DEFAULT 'PENDING',
    global_role_id  INTEGER, -- CLIENT_ADMIN or END_USER
    last_login_at   TIMESTAMP,                    -- Track last login
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(global_role_id) REFERENCES global_roles(id) ON DELETE SET NULL
);

-- Global roles that apply across the authorization server
CREATE TABLE global_roles (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            VARCHAR(50) UNIQUE NOT NULL, -- CLIENT_ADMIN, END_USER, SUPER_ADMIN
    description     TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- Password Reset Tokens
-- =========================
CREATE TABLE reset_tokens (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    token_hash      VARCHAR(255) UNIQUE NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN DEFAULT FALSE,
    ip_address      VARCHAR(100),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    used_at         TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =========================
-- Email Verification Tokens
-- =========================
CREATE TABLE verification_tokens (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    token_hash      VARCHAR(255) UNIQUE NOT NULL,
    email           VARCHAR(255) NOT NULL,        -- Email being verified
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    used_at         TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =========================
-- Mapping: Users ↔ Client Apps
-- =========================
CREATE TABLE user_client_map (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    client_id       VARCHAR(100) NOT NULL,
    status          VARCHAR(20) DEFAULT 'PENDING', -- PENDING, APPROVED, REVOKED
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, client_id),
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE
);

-- Track who owns/registered each client
CREATE TABLE client_ownership (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id       VARCHAR(100) NOT NULL,
    user_id         INTEGER NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id),
    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
-- =========================
-- Audit Logs
-- =========================
CREATE TABLE audit_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id       VARCHAR(100),  -- Changed from INTEGER
    user_id         INTEGER,
    action          VARCHAR(100) NOT NULL,
    ip_address      VARCHAR(100),
    user_agent      TEXT,
    metadata        TEXT,
    success         BOOLEAN DEFAULT TRUE,
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE SET NULL
);

-- Fix sessions
CREATE TABLE sessions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    client_id       VARCHAR(100),  -- Changed from INTEGER
    ip_address      VARCHAR(100),
    user_agent      TEXT,
    data            TEXT,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE
);

-- Add these indexes at the end
CREATE INDEX idx_user_client_map_user_id ON user_client_map(user_id);
CREATE INDEX idx_user_client_map_client_id ON user_client_map(client_id);
CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_expires_at ON sessions(expires_at);
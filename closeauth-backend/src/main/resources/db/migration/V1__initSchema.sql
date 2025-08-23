-- =========================
-- Tenant Applications
-- =========================
CREATE TABLE tenants (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) UNIQUE NOT NULL, -- e.g. 'app_a'
    client_id       VARCHAR(255) UNIQUE NOT NULL,
    client_secret   VARCHAR(255) NOT NULL,
    redirect_uris   TEXT NOT NULL,                -- JSON array format recommended
    scopes          TEXT,                         -- JSON array of allowed scopes
    theme_config    TEXT,                         -- JSON for branding (SQLite doesn't have native JSON)
    status          VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, DELETED
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- Global Users
-- =========================
CREATE TABLE users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    email           VARCHAR(255) UNIQUE NOT NULL,
    email_verified  BOOLEAN DEFAULT FALSE,
    phone           VARCHAR(20),
    phone_verified  BOOLEAN DEFAULT FALSE,        -- Added phone verification
    first_name      VARCHAR(100),                 -- Added basic profile fields
    last_name       VARCHAR(100),
    status          VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, DELETED, PENDING
    last_login_at   TIMESTAMP,                    -- Track last login
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- User Credentials (separate table for security)
-- =========================
CREATE TABLE credentials (
    user_id         INTEGER NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    algo            VARCHAR(50) DEFAULT 'bcrypt', -- e.g. bcrypt, argon2
    mfa_enabled     BOOLEAN DEFAULT FALSE,
    failed_attempts INTEGER DEFAULT 0,            -- Track failed login attempts
    locked_until    TIMESTAMP,                    -- Account lockout timestamp
    password_changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id),
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =========================
-- Mapping: Users ↔ Tenant Apps
-- =========================
CREATE TABLE user_tenant_map (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    tenant_id       INTEGER NOT NULL,
    role_id         INTEGER, -- assigned role
    status          VARCHAR(20) DEFAULT 'PENDING', -- PENDING, APPROVED, REVOKED
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, tenant_id),                   -- Prevent duplicate mappings
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE SET NULL
);

-- =========================
-- Mapping: Users ↔ Client Apps
-- =========================
CREATE TABLE user_tenant_map (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    tenant_id       INTEGER NOT NULL,
    role_id         INTEGER, -- assigned role
    status          VARCHAR(20) DEFAULT 'PENDING', -- PENDING, APPROVED, REVOKED
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, tenant_id),                   -- Prevent duplicate mappings
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE SET NULL
);

-- =========================
-- Roles & Permissions (Tenant Scoped)
-- =========================
CREATE TABLE roles (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id       INTEGER NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

CREATE TABLE permissions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id       INTEGER NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

CREATE TABLE role_permissions (
    role_id         INTEGER NOT NULL,
    permission_id   INTEGER NOT NULL,
    granted_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(role_id, permission_id),
    FOREIGN KEY(role_id) REFERENCES roles(id) ON DELETE CASCADE,
    FOREIGN KEY(permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

-- =========================
-- OAuth2 Authorization Codes (Missing Table)
-- =========================
CREATE TABLE authorization_codes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            VARCHAR(255) UNIQUE NOT NULL,
    user_id         INTEGER NOT NULL,
    tenant_id       INTEGER NOT NULL,
    client_id       VARCHAR(255) NOT NULL,
    redirect_uri    TEXT NOT NULL,
    scopes          TEXT,                         -- JSON array
    code_challenge  VARCHAR(255),                 -- PKCE
    code_challenge_method VARCHAR(10),            -- S256
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- =========================
-- Refresh Tokens (Opaque)
-- =========================
CREATE TABLE refresh_tokens (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    tenant_id       INTEGER NOT NULL,
    token_hash      VARCHAR(255) NOT NULL,
    device_fingerprint VARCHAR(255),
    ip_address      VARCHAR(100),
    user_agent      TEXT,
    status          VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, REVOKED, EXPIRED
    issued_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP,
    last_rotated_at TIMESTAMP,
    rotation_count  INTEGER DEFAULT 0,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
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
-- Audit Logs
-- =========================
CREATE TABLE audit_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id       INTEGER,
    user_id         INTEGER,
    action          VARCHAR(100) NOT NULL,        -- e.g. LOGIN_SUCCESS, PASSWORD_RESET
    ip_address      VARCHAR(100),
    user_agent      TEXT,
    metadata        TEXT,
    success         BOOLEAN DEFAULT TRUE,         -- Track success/failure
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE SET NULL
);

-- =========================
-- Sessions (Optional - for IdP session management)
-- =========================
CREATE TABLE sessions (
    id              VARCHAR(255) PRIMARY KEY,     -- Session ID
    user_id         INTEGER NOT NULL,
    tenant_id       INTEGER,
    ip_address      VARCHAR(100),
    user_agent      TEXT,
    data            TEXT,                         -- JSON session data
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

-- =========================
-- Indexes for Performance
-- =========================
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_credentials_user_id ON credentials(user_id);
CREATE INDEX idx_user_tenant_map_user_id ON user_tenant_map(user_id);
CREATE INDEX idx_user_tenant_map_tenant_id ON user_tenant_map(tenant_id);
CREATE INDEX idx_user_tenant_map_status ON user_tenant_map(status);
CREATE INDEX idx_roles_tenant_id ON roles(tenant_id);
CREATE INDEX idx_permissions_tenant_id ON permissions(tenant_id);
CREATE INDEX idx_authorization_codes_code ON authorization_codes(code);
CREATE INDEX idx_authorization_codes_expires_at ON authorization_codes(expires_at);
CREATE INDEX idx_refresh_tokens_user_tenant ON refresh_tokens(user_id, tenant_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_status ON refresh_tokens(status);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_reset_tokens_token_hash ON reset_tokens(token_hash);
CREATE INDEX idx_reset_tokens_expires_at ON reset_tokens(expires_at);
CREATE INDEX idx_verification_tokens_token_hash ON verification_tokens(token_hash);
CREATE INDEX idx_audit_logs_tenant_user ON audit_logs(tenant_id, user_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_expires_at ON sessions(expires_at);

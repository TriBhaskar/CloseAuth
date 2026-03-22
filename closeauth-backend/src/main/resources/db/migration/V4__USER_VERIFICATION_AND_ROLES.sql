-- =========================
-- Application-Specific Role Definitions
-- Each client can define custom roles for their application
-- =========================
CREATE TABLE application_roles (
    id              SERIAL PRIMARY KEY,
    client_id       VARCHAR(100) NOT NULL,
    role_name       VARCHAR(50) NOT NULL,           -- e.g., 'admin', 'manager', 'viewer', 'editor'
    description     TEXT,
    permissions     TEXT,                            -- JSON array of permissions
    is_default      BOOLEAN DEFAULT FALSE,          -- Assign this role to new users by default
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(client_id, role_name),
    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE
);

CREATE INDEX idx_app_roles_client ON application_roles(client_id);
CREATE INDEX idx_app_roles_default ON application_roles(client_id, is_default);

-- =========================
-- User Roles per Application
-- Extends user_client_map to include role assignment
-- =========================
CREATE TABLE user_application_roles (
    id              SERIAL PRIMARY KEY,
    user_client_map_id INTEGER NOT NULL,            -- References the user-client relationship
    application_role_id INTEGER NOT NULL,            -- The specific role in that application
    assigned_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_by     INTEGER,                         -- User ID who assigned this role

    UNIQUE(user_client_map_id, application_role_id),
    FOREIGN KEY(user_client_map_id) REFERENCES user_client_map(id) ON DELETE CASCADE,
    FOREIGN KEY(application_role_id) REFERENCES application_roles(id) ON DELETE CASCADE,
    FOREIGN KEY(assigned_by) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_user_app_roles_map ON user_application_roles(user_client_map_id);
CREATE INDEX idx_user_app_roles_role ON user_application_roles(application_role_id);

-- =========================
-- Application Registration Configuration
-- Client admins configure how users register for their application
-- =========================
CREATE TABLE application_registration_config (
    id                      SERIAL PRIMARY KEY,
    client_id               VARCHAR(100) NOT NULL UNIQUE,

    -- Verification method (can be combined with flags)
    verification_method     VARCHAR(50) NOT NULL
        CHECK(verification_method IN ('EMAIL', 'PHONE', 'ADMIN_APPROVAL', 'EMAIL_AND_PHONE', 'AUTO_APPROVE')),

    -- Additional flags
    require_email_verification   BOOLEAN DEFAULT TRUE,
    require_phone_verification   BOOLEAN DEFAULT FALSE,
    require_admin_approval       BOOLEAN DEFAULT FALSE,

    -- Auto-approval settings
    auto_approve_domains    TEXT,                    -- JSON array of whitelisted email domains

    -- Registration flow settings
    allow_self_registration BOOLEAN DEFAULT TRUE,    -- Can users register themselves?
    registration_enabled    BOOLEAN DEFAULT TRUE,    -- Is registration open?

    -- Required fields during registration
    require_phone           BOOLEAN DEFAULT FALSE,
    require_first_name      BOOLEAN DEFAULT TRUE,
    require_last_name       BOOLEAN DEFAULT TRUE,
    custom_fields           TEXT,                    -- JSON array of custom fields

    -- Email settings
    verification_email_template TEXT,                -- Custom email template
    verification_token_expiry   INTEGER DEFAULT 24,  -- Hours

    -- Phone settings
    phone_verification_method VARCHAR(20)
        CHECK(phone_verification_method IN ('SMS', 'CALL', 'WHATSAPP')) DEFAULT 'SMS',
    phone_verification_token_expiry INTEGER DEFAULT 10, -- Minutes

    -- Admin approval settings
    approval_notification_email VARCHAR(255),        -- Email to notify for approvals
    approval_required_message   TEXT,                -- Custom message shown to user

    -- Welcome/Onboarding
    welcome_email_enabled   BOOLEAN DEFAULT TRUE,
    redirect_after_registration VARCHAR(500),        -- Where to redirect after successful registration

    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY(client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE
);

CREATE INDEX idx_app_reg_config_client ON application_registration_config(client_id);

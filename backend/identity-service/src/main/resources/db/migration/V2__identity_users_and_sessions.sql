CREATE TABLE identity_role (
    role_code VARCHAR(40) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL
);

CREATE TABLE identity_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    normalized_email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_identity_user_normalized_email UNIQUE (normalized_email)
);

CREATE TABLE identity_user_role (
    user_id UUID NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    role_code VARCHAR(40) NOT NULL REFERENCES identity_role(role_code),
    CONSTRAINT pk_identity_user_role PRIMARY KEY (user_id, role_code)
);

CREATE TABLE identity_refresh_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    token_family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    csrf_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_identity_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_identity_refresh_user ON identity_refresh_session(user_id);
CREATE INDEX ix_identity_refresh_family ON identity_refresh_session(token_family_id);
CREATE INDEX ix_identity_refresh_expiry ON identity_refresh_session(expires_at);

INSERT INTO identity_role (role_code, display_name) VALUES
    ('CRM_ADMIN', 'CRM Administrator'),
    ('CRM_MANAGER', 'CRM Manager'),
    ('CRM_AGENT', 'CRM Agent'),
    ('VENDOR', 'Vendor');
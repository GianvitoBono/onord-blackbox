CREATE TABLE dashboard_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username text NOT NULL UNIQUE
        CHECK (username = lower(username) AND username ~ '^[a-z0-9._-]{1,64}$'),
    password_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    disabled_at timestamptz
);

CREATE TABLE dashboard_sessions (
    session_hash bytea PRIMARY KEY CHECK (octet_length(session_hash) = 32),
    user_id uuid NOT NULL REFERENCES dashboard_users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX dashboard_sessions_user_idx ON dashboard_sessions (user_id);
CREATE INDEX dashboard_sessions_expiry_idx ON dashboard_sessions (expires_at);

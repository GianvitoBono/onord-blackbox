CREATE TABLE pending_device_auth (
    device_id uuid PRIMARY KEY,
    candidate_token_hash bytea NOT NULL CHECK (octet_length(candidate_token_hash) = 32),
    candidate_token_eligible boolean NOT NULL,
    reason text NOT NULL CHECK (reason IN ('unknown_device_id', 'token_mismatch', 'device_revoked')),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    attempt_count integer NOT NULL DEFAULT 1 CHECK (attempt_count > 0),
    last_batch_id uuid NOT NULL
);
CREATE INDEX pending_device_auth_last_seen_idx ON pending_device_auth (last_seen_at DESC);

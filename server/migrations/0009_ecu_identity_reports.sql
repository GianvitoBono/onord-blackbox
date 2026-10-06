CREATE TABLE ecu_identity_reports (
    device_id uuid PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    observed_at timestamptz NOT NULL,
    report jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (octet_length(report::text) <= 65536)
);

CREATE INDEX ecu_identity_reports_observed_at_idx
    ON ecu_identity_reports (observed_at DESC);

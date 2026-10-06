CREATE TABLE diagnostics_reports (
    device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    vehicle_id uuid NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    observed_at timestamptz NOT NULL,
    report jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, observed_at),
    CHECK (octet_length(report::text) <= 65536)
);

CREATE INDEX diagnostics_reports_observed_at_idx
    ON diagnostics_reports (vehicle_id, observed_at DESC, device_id);

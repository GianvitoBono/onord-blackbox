CREATE TABLE obd_raw_replies (
    device_id uuid NOT NULL REFERENCES devices(id),
    observed_at timestamptz NOT NULL,
    reply_id uuid NOT NULL,
    trip_id uuid NOT NULL REFERENCES trips(id),
    mode smallint NOT NULL CHECK (mode BETWEEN 0 AND 255),
    pid smallint CHECK (pid BETWEEN 0 AND 255),
    command text NOT NULL CHECK (octet_length(command) <= 32),
    ecu_id text CHECK (ecu_id IS NULL OR octet_length(ecu_id) <= 16),
    response_bytes bytea NOT NULL,
    raw_response text NOT NULL CHECK (octet_length(raw_response) <= 4096),
    parse_status text NOT NULL CHECK (parse_status IN ('complete','captured','unsupported','malformed','adapter_error','timeout','error')),
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, observed_at, reply_id)
);
SELECT create_hypertable('obd_raw_replies', by_range('observed_at'), if_not_exists => TRUE);
CREATE INDEX obd_raw_trip_time_idx ON obd_raw_replies (trip_id, observed_at DESC);

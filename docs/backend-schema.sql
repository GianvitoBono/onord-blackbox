-- Schema iniziale v1. Schema applicato: server/migrations/0001_init.sql
-- server/migrations/0002_metric_geo.sql (metriche + PostGIS),
-- server/migrations/0003_dashboard_auth.sql e server/migrations/0004_gps_deltas.sql.
-- Questo file documenta la base storica, non va eseguito dopo le migrazioni.
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE vehicles (
    id uuid PRIMARY KEY,
    display_name text NOT NULL,
    vin text UNIQUE,
    make text,
    model text,
    model_year smallint,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id uuid PRIMARY KEY,
    vehicle_id uuid REFERENCES vehicles(id),
    display_name text NOT NULL,
    token_hash bytea NOT NULL,
    token_revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE device_vehicle_assignments (
    device_id uuid NOT NULL REFERENCES devices(id),
    vehicle_id uuid NOT NULL REFERENCES vehicles(id),
    assigned_at timestamptz NOT NULL,
    unassigned_at timestamptz,
    PRIMARY KEY (device_id, assigned_at),
    CHECK (unassigned_at IS NULL OR unassigned_at > assigned_at)
);
CREATE UNIQUE INDEX one_current_vehicle_per_device
    ON device_vehicle_assignments (device_id) WHERE unassigned_at IS NULL;

CREATE TABLE trips (
    id uuid PRIMARY KEY,
    device_id uuid NOT NULL REFERENCES devices(id),
    vehicle_id uuid NOT NULL REFERENCES vehicles(id),
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    start_reason text NOT NULL,
    end_reason text,
    distance_gps_m double precision,
    distance_obd_m double precision,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX trips_vehicle_started_idx ON trips (vehicle_id, started_at DESC);

CREATE TABLE ingest_batches (
    device_id uuid NOT NULL REFERENCES devices(id),
    batch_id uuid NOT NULL,
    payload_sha256 bytea NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT now(),
    gps_count integer NOT NULL,
    obd_count integer NOT NULL,
    device_count integer NOT NULL,
    PRIMARY KEY (device_id, batch_id)
);

CREATE TABLE gps_samples (
    device_id uuid NOT NULL REFERENCES devices(id),
    observed_at timestamptz NOT NULL,
    sample_id uuid NOT NULL,
    trip_id uuid NOT NULL REFERENCES trips(id),
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    altitude_m double precision,
    speed_mps real,
    bearing_deg real,
    horizontal_accuracy_m real,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, observed_at, sample_id)
);
SELECT create_hypertable('gps_samples', by_range('observed_at'), if_not_exists => TRUE);
CREATE INDEX gps_trip_time_idx ON gps_samples (trip_id, observed_at DESC);

CREATE TABLE obd_measurements (
    device_id uuid NOT NULL REFERENCES devices(id),
    observed_at timestamptz NOT NULL,
    sample_id uuid NOT NULL,
    trip_id uuid NOT NULL REFERENCES trips(id),
    pid text NOT NULL,
    value_numeric double precision NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, observed_at, sample_id)
);
SELECT create_hypertable('obd_measurements', by_range('observed_at'), if_not_exists => TRUE);
CREATE INDEX obd_trip_pid_time_idx ON obd_measurements (trip_id, pid, observed_at DESC);

CREATE TABLE device_samples (
    device_id uuid NOT NULL REFERENCES devices(id),
    observed_at timestamptz NOT NULL,
    sample_id uuid NOT NULL,
    trip_id uuid REFERENCES trips(id),
    power_connected boolean,
    battery_pct real,
    battery_temp_c real,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, observed_at, sample_id)
);
SELECT create_hypertable('device_samples', by_range('observed_at'), if_not_exists => TRUE);
CREATE INDEX device_samples_trip_time_idx ON device_samples (trip_id, observed_at DESC);

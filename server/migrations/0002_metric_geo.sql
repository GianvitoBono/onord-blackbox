CREATE EXTENSION IF NOT EXISTS postgis;

-- Raw GPS fixes stay in their dedicated hypertable. Geography gives meter-based
-- distance predicates without copying a point into every OBD measurement.
ALTER TABLE gps_samples ADD COLUMN position geography(Point, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED;
CREATE INDEX gps_position_gix ON gps_samples USING gist (position);

CREATE TABLE metric_definitions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL UNIQUE,
    source text NOT NULL,
    unit text NOT NULL,
    value_kind text NOT NULL DEFAULT 'numeric' CHECK (value_kind IN ('numeric', 'boolean')),
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (length(name) BETWEEN 1 AND 96)
);

-- Typed scope prevents vehicle/trip history from changing when a device moves.
-- labels carry only bounded channel attributes; no VIN, coordinates or timestamps.
CREATE TABLE metric_series (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    metric_id bigint NOT NULL REFERENCES metric_definitions(id),
    device_id uuid NOT NULL REFERENCES devices(id),
    vehicle_id uuid NOT NULL REFERENCES vehicles(id),
    trip_id uuid REFERENCES trips(id),
    labels jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(labels) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (metric_id, device_id, vehicle_id, trip_id, labels)
);
CREATE INDEX metric_series_trip_idx ON metric_series (trip_id, metric_id);
CREATE INDEX metric_series_vehicle_idx ON metric_series (vehicle_id, metric_id);

CREATE TABLE metric_samples (
    series_id uuid NOT NULL REFERENCES metric_series(id),
    observed_at timestamptz NOT NULL,
    sample_id uuid NOT NULL,
    value_numeric double precision NOT NULL
        CHECK (value_numeric > '-Infinity'::float8 AND value_numeric < 'Infinity'::float8),
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (series_id, observed_at, sample_id)
);
SELECT create_hypertable('metric_samples', by_range('observed_at'), if_not_exists => TRUE);
CREATE INDEX metric_samples_time_idx ON metric_samples (observed_at DESC);

INSERT INTO metric_definitions (name, source, unit, value_kind, description) VALUES
 ('obd.pid.010c', 'obd', 'rpm', 'numeric', 'Engine RPM'),
 ('obd.pid.010d', 'obd', 'km/h', 'numeric', 'Vehicle speed'),
 ('obd.pid.0104', 'obd', '%', 'numeric', 'Calculated engine load'),
 ('obd.pid.0111', 'obd', '%', 'numeric', 'Throttle position'),
 ('obd.pid.0105', 'obd', '°C', 'numeric', 'Coolant temperature'),
 ('obd.pid.010f', 'obd', '°C', 'numeric', 'Intake air temperature'),
 ('obd.pid.0110', 'obd', 'g/s', 'numeric', 'Mass air flow'),
 ('obd.pid.012f', 'obd', '%', 'numeric', 'Fuel level'),
 ('obd.pid.0142', 'obd', 'V', 'numeric', 'Control module voltage'),
 ('gps.speed_mps', 'gps', 'm/s', 'numeric', 'GPS speed'),
 ('gps.altitude_m', 'gps', 'm', 'numeric', 'GPS altitude'),
 ('gps.accuracy_m', 'gps', 'm', 'numeric', 'Horizontal accuracy'),
 ('device.battery_pct', 'device', '%', 'numeric', 'Phone battery level'),
 ('device.battery_temp_c', 'device', '°C', 'numeric', 'Phone battery temperature'),
 ('device.power_connected', 'device', 'bool', 'boolean', 'External power connected');

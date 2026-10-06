ALTER TABLE ingest_batches
    ADD COLUMN event_count integer NOT NULL DEFAULT 0;

CREATE TABLE trip_events (
    event_id uuid PRIMARY KEY,
    trip_id uuid NOT NULL REFERENCES trips(id),
    stop_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('stop_start', 'stop_end')),
    observed_at timestamptz NOT NULL,
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    UNIQUE (trip_id, stop_id, kind)
);

CREATE INDEX trip_events_trip_time_idx ON trip_events (trip_id, observed_at, event_id);

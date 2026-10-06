-- Per-fix distance/time to the previous GPS fix in the same trip.
-- A trip's first fix has NULL deltas. Equal timestamps use sample_id UUID as a stable tie-breaker.
ALTER TABLE gps_samples
    ADD COLUMN delta_sec double precision,
    ADD COLUMN delta_m double precision,
    ADD CONSTRAINT gps_delta_pair_check CHECK ((delta_sec IS NULL) = (delta_m IS NULL)),
    ADD CONSTRAINT gps_delta_sec_check CHECK (delta_sec IS NULL OR (delta_sec >= 0 AND delta_sec < 'Infinity'::float8)),
    ADD CONSTRAINT gps_delta_m_check CHECK (delta_m IS NULL OR (delta_m >= 0 AND delta_m < 'Infinity'::float8));

CREATE INDEX gps_trip_order_idx ON gps_samples (trip_id, observed_at, sample_id);

-- Preserve existing fixes and fill derived values without changing their coordinates or IDs.
WITH ordered AS (
    SELECT device_id, observed_at, sample_id,
           EXTRACT(EPOCH FROM observed_at - LAG(observed_at) OVER w)::double precision AS elapsed_sec,
           ST_Distance(position, LAG(position) OVER w) AS traveled_m
    FROM gps_samples
    WINDOW w AS (PARTITION BY trip_id ORDER BY observed_at, sample_id)
)
UPDATE gps_samples AS g
SET delta_sec = o.elapsed_sec, delta_m = o.traveled_m
FROM ordered AS o
WHERE g.device_id = o.device_id AND g.observed_at = o.observed_at AND g.sample_id = o.sample_id
  AND o.elapsed_sec IS NOT NULL;

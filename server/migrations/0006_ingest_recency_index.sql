CREATE INDEX ingest_batches_device_accepted_idx
    ON ingest_batches (device_id, accepted_at DESC);

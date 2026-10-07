-- Preserve and accept incomplete ISO-TP replies already queued by Android.
ALTER TABLE obd_raw_replies DROP CONSTRAINT obd_raw_replies_parse_status_check;
ALTER TABLE obd_raw_replies ADD CONSTRAINT obd_raw_replies_parse_status_check
    CHECK (parse_status IN ('complete','captured','unsupported','malformed',
        'incomplete_or_malformed','adapter_error','timeout','error'));

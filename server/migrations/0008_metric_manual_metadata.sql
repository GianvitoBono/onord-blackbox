ALTER TABLE metric_definitions
    ADD COLUMN manually_defined boolean NOT NULL DEFAULT false;

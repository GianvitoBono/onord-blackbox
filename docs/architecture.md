# Vehicle Blackbox architecture

## Decisions

MVP uses a foreground location-type service as owner of trip lifecycle and sampling. User starts persistent monitor from visible activity once; service dynamically receives power broadcasts and reconciles current charging state. GPS request runs only during a trip. Android stock restricts cold foreground-service starts from background, so a manifest power receiver alone cannot reliably launch a location logger. Automatic boot after force-stop remains outside stock MVP. Room is source of truth with WAL. GPS and battery currently use nullable timestamped samples; OBD will use a separate collector and likely a separate local stream before M2 sync.

Power is a useful installation trigger, not a reliable ignition signal. A fixed 90-second grace period is used initially. On process recovery, an orphan ACTIVE trip is closed as `unexpected_shutdown`; silently resuming risks joining separate drives. GPS uses Fused Location at a requested 1 Hz and persists each fix; a separate 1 Hz battery sample captures charging level and temperature. Android 11+ background location requires explicit user grant through app settings. OxygenOS may still kill the resident service; physical testing must measure this behavior.

Sync stores immutable UUID batches and payloads in Room, then WorkManager uploads over HTTPS only when Wi-Fi is active; samples become synced only after matching ACK. Device ID and endpoint are local configuration; token is encrypted by Android Keystore. No analytics/cloud SDK. WorkManager is a background retry mechanism and does not drive logger lifecycle.

Backend plan: Rust/Axum/SQLx with TimescaleDB, React/TypeScript/Vite dashboard. `backend-architecture.md` defines vehicle/trip model and ingest protocol; `backend-schema.sql` contains draft SQL. Current Android entity is a transitional wide sample and must evolve before OBD sync.

## Layers

- `service`: dynamic power broadcast monitoring and current power state.
- `sync`: persisted outbox, WorkManager and HTTPS API client.
- `trip`: pure state machine.
- `storage`: Room entities/DAOs, WAL.
- `location`: Fused Location behind collector interface; `obd`: future transport boundary.
- `ui`: diagnostics and configuration.

OBD should use a serialized command channel; discover supported PID bitmap first; timeout/reconnect per transport. ELM327-compatible devices differ, so initialization and parser must tolerate adapter errors.

## Android appliance constraints

Do not depend on auto-start after force-stop. Configure OxygenOS battery optimization exemption and background activity; grant precise location and "Allow all the time". Screen-off operation needs persistent foreground notification. Validate charging heat and phone thermal behavior in parked-car conditions. Root, auto-boot, shutdown and charge limiting are future work; see `embedded-mode.md`. Android restriction references: [background foreground-service start](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and [background location permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background).

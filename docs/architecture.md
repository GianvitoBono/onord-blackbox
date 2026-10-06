# Vehicle Blackbox architecture

## Decisions

Foreground location service owns trip lifecycle and sampling. User starts persistent monitor from visible activity once. Idle GPS runs at 15-second requested interval; confirmed movement opens a trip and switches to 1-second GPS plus OBD/battery sampling. Recent idle fixes are saved with the new trip, preserving its start. USB power remains a sampled diagnostic value and never opens or closes a trip. Android stock restricts cold foreground-service starts from background; automatic boot after force-stop remains outside stock MVP. Room with WAL remains source of truth.

Stationary GPS for two minutes creates `stop_start`; renewed motion creates matching `stop_end` with same `stopId`. Only 90 continuous minutes stationary close trip and its open stop. Missing GPS alone does not prove vehicle stopped. On process recovery, recent active trip is resumed; trip with last fix older than 90 minutes is closed as `recovery_timeout`. This prevents engine stop/start, refueling and short breaks from splitting journeys. Motion thresholds and OxygenOS behavior need calibration with road traces. Android 11+ background location needs explicit grant.

Sync stores immutable UUID batches and payloads in Room, then WorkManager uploads over HTTPS only when Wi-Fi is active; samples and stop boundaries become synced only after matching ACK. Device ID and endpoint are local configuration; token is encrypted by Android Keystore. Old pending batches remain readable after Room v4→v5 migration. New `tripEvents` payloads require server migration 0007 first; old Android payloads remain valid on new backend. No analytics/cloud SDK. WorkManager does not drive logger lifecycle.

Backend plan: Rust/Axum/SQLx with TimescaleDB, React/TypeScript/Vite dashboard. `backend-architecture.md` defines vehicle/trip model and ingest protocol; `backend-schema.sql` contains draft SQL. Current Android entity is a transitional wide sample and must evolve before OBD sync.

## Layers

- `service`: foreground monitor, GPS sampling modes, OBD and battery collection.
- `sync`: persisted outbox, WorkManager and HTTPS API client.
- `trip`: GPS movement and stop detector.
- `storage`: Room entities/DAOs, WAL.
- `location`: Fused Location behind collector interface; `obd`: future transport boundary.
- `ui`: diagnostics and configuration.

OBD should use a serialized command channel; discover supported PID bitmap first; timeout/reconnect per transport. ELM327-compatible devices differ, so initialization and parser must tolerate adapter errors.

## Android appliance constraints

Do not depend on auto-start after force-stop. Configure OxygenOS battery optimization exemption and background activity; grant precise location and "Allow all the time". Screen-off operation needs persistent foreground notification. Validate charging heat and phone thermal behavior in parked-car conditions. Root, auto-boot, shutdown and charge limiting are future work; see `embedded-mode.md`. Android restriction references: [background foreground-service start](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and [background location permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background).

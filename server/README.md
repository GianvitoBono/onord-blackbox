# OpNord backend MVP

Axum + SQLx API backed by PostgreSQL with TimescaleDB and PostGIS. The server applies versioned SQL migrations at startup. It exposes device bearer ingest and dashboard sessions through username/password login. Ingest writes legacy typed tables and metric series in one transaction; see [metric/geospatial model](../docs/metrics-geospatial.md).

## Run

Use a PostgreSQL/TimescaleDB database with TimescaleDB and PostGIS available. `../compose.yaml` pins the compatible local image. Configure:

```sh
export DATABASE_URL='postgres://opnord:opnord@localhost:5432/opnord'
export BIND_ADDR='127.0.0.1:8080' # optional; default shown
export DASHBOARD_COOKIE_SECURE=false # local HTTP only; true behind HTTPS
cd server
CARGO_HOME=../.cache/cargo CARGO_TARGET_DIR=../.cache/cargo-target cargo run
```

After migrations, run `cargo run --bin provision_dashboard` with `DATABASE_URL` set. This creates a local `admin` user with random password in `.data/dashboard-login.txt` at the repository root (mode `0600`). `cargo run --bin provision_dashboard -- --reset` rotates it and revokes sessions. Passwords are Argon2id hashes in `dashboard_users`; opaque 12-hour session cookies are HttpOnly and SameSite=Strict, with only SHA-256 session hashes stored in `dashboard_sessions`. Cookie `Secure` defaults to true. `DASHBOARD_COOKIE_SECURE=false` is accepted only with loopback `BIND_ADDR` for local HTTP. Public deployment needs HTTPS termination and login rate limiting.
`cargo run --bin provision_device -- --rotate` rotates the device token to a 20-character random base32 value grouped for manual entry. It updates `devices.token_hash` and the credentials JSON together while retaining device/vehicle IDs. The previous token stops working immediately.
The Android device ID is a stable UUID, separate from its display name. If that UUID changes while local trips still have unsent data, the logger rebuilds pending batches under the new ID. On ingest, an authenticated new device assigned to the same vehicle adopts the existing trip ID. Existing samples retain their original `device_id` for provenance; new samples use the new device ID. A device assigned to another vehicle still receives HTTP 409 `trip_conflict`.
The dashboard also lists devices and rotates a generated or user-supplied token after account-password confirmation. Custom tokens require 16–128 ASCII letters, digits, hyphens, or underscores. It returns the new token once with `Cache-Control: no-store`, hashes it in the database, and updates the credentials JSON when that file belongs to the device.
For the isolated `compose.local.yaml` stack, `DASHBOARD_LOCAL_HTTP_CONTAINER=true` additionally allows an internal Docker bind with insecure cookies; only its Caddy port is published to host loopback. Never set this override on an internet-facing deployment.

`/healthz` is process liveness; `/readyz` returns 503 until PostgreSQL responds. Put server-generated device token hashes in `devices.token_hash`: SHA-256 of the exact raw bearer token bytes. Plaintext tokens are kept in the private credentials JSON for bootstrap and never stored in the database or logged. Create a matching open row in `device_vehicle_assignments` for each assigned device.
Backend writes JSON request and database-error logs to stderr, visible through `docker compose logs backend`. Ingest 401 logs include `deviceId`, `batchId`, and a reason (`missing_bearer`, `unknown_device_id`, `device_revoked`, `token_mismatch`); they never include the token or Authorization header.
Failed authenticated requests with a bearer token are retained for seven days in `pending_device_auth`, capped at 200 device IDs. The table stores a SHA-256 token hash and metadata, never the bearer token. The dashboard can approve an unknown device using its most recently observed token hash after password confirmation and vehicle selection. Registered devices with token mismatch require explicit token rotation.

## API contract (schema version 1)

All API errors are JSON `{ "error": { "code": "...", "message": "..." } }`. Datetimes are RFC 3339 with a timezone; UUIDs use the standard UUID string form. Unknown fields are rejected. Ingest body limit is 2 MiB and max total samples per batch is 10,000.

### `POST /api/v1/telemetry/batches`

Header: `Authorization: Bearer <device-token>`.

```json
{
  "schemaVersion": 1,
  "deviceId": "00000000-0000-4000-8000-000000000001",
  "batchId": "00000000-0000-4000-8000-000000000002",
  "trip": {
    "id": "00000000-0000-4000-8000-000000000003",
    "startedAt": "2026-10-05T12:00:00Z",
    "endedAt": "2026-10-05T12:10:00Z",
    "startReason": "activity",
    "endReason": "stationary",
    "distanceGpsM": 1532.4,
    "distanceObdM": null
  },
  "gpsSamples": [{
    "sampleId": "00000000-0000-4000-8000-000000000004",
    "observedAt": "2026-10-05T12:00:01Z",
    "latitude": 45.4642,
    "longitude": 9.1900,
    "altitudeM": 122.0,
    "speedMps": 8.2,
    "bearingDeg": 90.0,
    "horizontalAccuracyM": 5.0
  }],
  "obdSamples": [{
    "sampleId": "00000000-0000-4000-8000-000000000005",
    "observedAt": "2026-10-05T12:00:03Z",
    "pid": "010C",
    "value": 1750.0,
    "name": "Engine RPM",
    "unit": "rpm"
  }],
  "deviceSamples": [{
    "sampleId": "00000000-0000-4000-8000-000000000006",
    "observedAt": "2026-10-05T12:00:00Z",
    "tripId": "00000000-0000-4000-8000-000000000003",
    "powerConnected": true,
    "batteryPct": 88.0,
    "batteryTempC": 29.5
  }]
}
```

`trip` is required; every sample array and `tripEvents` are optional and default to empty, preserving older clients. For a batch, every GPS/OBD row is associated with `trip.id`; device rows use their `tripId` when supplied, otherwise `trip.id`. `tripEvents` entries have `{ "eventId": "UUID", "stopId": "UUID", "kind": "stop_start|stop_end", "observedAt": "RFC3339", "latitude": 45.46, "longitude": 9.19 }`. Both ends of a stop share `stopId`; each event has its own `eventId`. Event IDs must be unique across all sample/event IDs in the batch. Event coordinates and timestamps (not before trip start) are validated. Event inserts share the batch transaction; repeating the same event is idempotent, while reusing an event/stop identity with different data returns HTTP 409. The combined sample and event count has a 10,000-row batch cap. `pid` is the stable, caller-defined sensor key; `value` is numeric. Standard `01XX` keys become `obd.pid.01xx`; `calc.*` keys become `obd.calc.*` and represent derived values. Optional `name` and `unit` preserve display metadata in the metric catalog. Device must have an assigned vehicle. Sample UUID uniqueness is scoped by device, observed time, and sample id as required by Timescale hypertables.

Success is HTTP 200 with `{ "batchId": "...", "accepted": true, "gpsAccepted": 1, "obdAccepted": 1, "deviceAccepted": 1, "eventsAccepted": 2, "receivedAt": "...", "schemaVersion": 1 }`; counts report rows in the accepted request. `eventsAccepted` is zero for batches without events, including retries of batches accepted before migration 0007. Receipt and writes commit atomically. The same device/batch UUID and canonical JSON payload returns the stored receipt; reusing the batch UUID for a different payload returns HTTP 409 `batch_id_conflict`. JSON object key order does not affect the payload hash; array order does.

### Dashboard reads

Dashboard login endpoints:

- `POST /api/v1/auth/login` with JSON `{ "username": "admin", "password": "..." }` sets an HttpOnly session cookie and returns `{ "username": "admin" }`.
- `GET /api/v1/auth/session` returns current username or HTTP 401.
- `POST /api/v1/auth/logout` revokes session and clears cookie.
- `GET /api/v1/devices` lists devices and their latest accepted ingest time without exposing token hashes.
- `GET /api/v1/devices/pending` lists recent failed device IDs, reasons, timestamps, and attempt counts. Only `unknown_device_id` entries with a valid 16–128 character token are `approvable`.
- `POST /api/v1/devices/pending/{deviceId}/approve` with `{ "password": "...", "vehicleId": "...", "displayName": "..." }` creates the device, assigns it to the selected vehicle, and trusts the hash from the last rejected request.
- `POST /api/v1/devices/{deviceId}/rotate-token` with `{ "password": "...", "deviceToken": "optional-custom-token" }` rechecks the dashboard password, replaces the active device token, and returns `{ "deviceId": "...", "deviceToken": "...", "credentialsFileUpdated": true }` once. Omit `deviceToken` for a generated value. The previous token is invalid immediately.

Dashboard reads require the session cookie and return arrays directly. Device bearer tokens never authenticate dashboard reads.

- `GET /api/v1/vehicles` → `[{ "id": "...", "displayName": "Car", "make": null, "model": null, "modelYear": null, "currentDeviceId": "..." }]`.
- `POST /api/v1/vehicles` and `PUT /api/v1/vehicles/{vehicleId}` accept `{ "displayName": "Giulietta", "make": "Alfa Romeo", "model": "Giulietta", "modelYear": 2020 }` to create or update a vehicle.
- `GET /api/v1/vehicles/{vehicleId}/status` → latest trip ID, its newest GPS point, and latest reading per metric in that trip. Each reading includes its own observation timestamp so stale values are visible.
- `GET /api/v1/vehicles/{vehicleId}/trips?limit=100` → `[{ "id": "...", "startedAt": "...", "endedAt": "...", "startReason": "activity", "endReason": "stationary", "distanceGpsM": 1532.4, "distanceObdM": null }]`. Default limit 100, max 500.
- `GET /api/v1/trips/{tripId}/gps?limit=5000` → `[{ "sampleId": "...", "observedAt": "...", "deltaSec": 1.0, "deltaM": 8.2, "latitude": 45.4642, "longitude": 9.19, "altitudeM": 122.0, "speedMps": 8.2, "bearingDeg": 90.0, "horizontalAccuracyM": 5.0 }]`. Default 5000, max 10000, oldest first. First point has null deltas. Ordering uses `(observedAt, sampleId)`; `deltaM` is PostGIS ground distance from preceding point. Continue with `afterAt` and `afterSampleId` set to the last row's values to fetch the next page.
- `GET /api/v1/trips/{tripId}/events` → `[{ "eventId": "...", "stopId": "...", "kind": "stop_start", "observedAt": "...", "latitude": 45.46, "longitude": 9.19 }]`, ordered by `(observedAt, eventId)`. Dashboard session required. Each stop is represented by paired `stop_start` and `stop_end` events sharing `stopId`; render both as markers over the trip GPS line.
- `GET /api/v1/trips/{tripId}/metrics?name=obd.pid.010c&from=2026-10-05T12%3A00%3A00Z&to=2026-10-05T13%3A00%3A00Z&limit=1000` → bounded numeric samples with `sampleId`, `observedAt`, `name`, `unit`, `value`. Max limit 5000.
- `GET /api/v1/trips/{tripId}/metric-catalog` → metrics present in that trip as `[{ "name": "obd.pid.010c", "unit": "rpm", "description": "Engine RPM" }]`.
- `PATCH /api/v1/metrics/{name}` updates a discovered metric definition globally: `{ "displayName": "Engine RPM", "unit": "rpm" }`. Requires dashboard session cookie. `name` is the stable metric key (for example `obd.pid.010c`). The response has `{ "name", "unit", "description" }`; `description` stores the display name and appears in the metric catalog. Unknown keys return 404. Manual metadata is protected from later device ingest updates.
- `GET /api/v1/trips/{tripId}/telemetry-at?at={rfc3339}&toleranceMs=2000` → nearest reading per metric inside the requested time window, with `sampleId`, `observedAt`, `name`, `unit`, `value`. Max tolerance 30 s and max 1000 metrics. Used by the GPS point inspector.
- `GET /api/v1/geo/correlations?vehicleId={uuid}&name=obd.pid.010c&from={rfc3339}&to={rfc3339}&longitude=9.19&latitude=45.46&radiusM=500&toleranceMs=2000&limit=500` → GPS fixes in radius with nearest metric sample inside explicit time tolerance. Max radius 10 km, tolerance 30 s, limit 1000. Coordinates use WGS84 and radius uses meters.

The GPS read API supports a cursor. Metrics and geospatial reads require time bounds and cap returned rows; large historical queries still need cursor/downsample APIs.

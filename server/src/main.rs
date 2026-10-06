use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use opnord_server::password::{hash_password, verify_password};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::{postgres::PgPoolOptions, PgPool, Postgres, Row, Transaction};
use std::{
    collections::{BTreeMap, HashSet},
    net::SocketAddr,
    sync::OnceLock,
};
use uuid::Uuid;

#[derive(Clone)]
struct App {
    db: PgPool,
    cookie_secure: bool,
}
#[derive(Serialize)]
struct ApiError {
    error: ErrorBody,
}
#[derive(Serialize)]
struct ErrorBody {
    code: &'static str,
    message: String,
}
struct HttpError(StatusCode, &'static str, String);
impl IntoResponse for HttpError {
    fn into_response(self) -> Response<Body> {
        (
            self.0,
            Json(ApiError {
                error: ErrorBody {
                    code: self.1,
                    message: self.2,
                },
            }),
        )
            .into_response()
    }
}
type Result<T> = std::result::Result<T, HttpError>;
fn err(status: StatusCode, code: &'static str, msg: impl Into<String>) -> HttpError {
    HttpError(status, code, msg.into())
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Batch {
    schema_version: u32,
    device_id: Uuid,
    batch_id: Uuid,
    trip: Trip,
    #[serde(default)]
    gps_samples: Vec<Gps>,
    #[serde(default)]
    obd_samples: Vec<Obd>,
    #[serde(default)]
    device_samples: Vec<DeviceSample>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Trip {
    id: Uuid,
    started_at: DateTime<Utc>,
    ended_at: Option<DateTime<Utc>>,
    start_reason: String,
    end_reason: Option<String>,
    distance_gps_m: Option<f64>,
    distance_obd_m: Option<f64>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Gps {
    sample_id: Uuid,
    observed_at: DateTime<Utc>,
    latitude: f64,
    longitude: f64,
    altitude_m: Option<f64>,
    speed_mps: Option<f32>,
    bearing_deg: Option<f32>,
    horizontal_accuracy_m: Option<f32>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Obd {
    sample_id: Uuid,
    observed_at: DateTime<Utc>,
    pid: String,
    value: f64,
    /// Optional display metadata supplied by richer adapters such as Car Scanner.
    /// PID remains the stable identity; labels never become metric names.
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    unit: Option<String>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DeviceSample {
    sample_id: Uuid,
    observed_at: DateTime<Utc>,
    trip_id: Option<Uuid>,
    power_connected: Option<bool>,
    battery_pct: Option<f32>,
    battery_temp_c: Option<f32>,
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Ack {
    batch_id: Uuid,
    accepted: bool,
    gps_accepted: i32,
    obd_accepted: i32,
    device_accepted: i32,
    received_at: DateTime<Utc>,
    schema_version: u32,
}

fn canonical(v: Value) -> Value {
    match v {
        Value::Array(xs) => Value::Array(xs.into_iter().map(canonical).collect()),
        Value::Object(m) => {
            let sorted: BTreeMap<_, _> = m.into_iter().map(|(k, v)| (k, canonical(v))).collect();
            serde_json::to_value(sorted).unwrap()
        }
        other => other,
    }
}
async fn health() -> &'static str {
    "ok"
}
async fn ready(State(s): State<App>) -> Result<&'static str> {
    sqlx::query("SELECT 1").execute(&s.db).await.map_err(|_| {
        err(
            StatusCode::SERVICE_UNAVAILABLE,
            "not_ready",
            "database unavailable",
        )
    })?;
    Ok("ok")
}
async fn ingest(
    State(s): State<App>,
    headers: HeaderMap,
    Json(batch): Json<Batch>,
) -> Result<Json<Ack>> {
    if batch.schema_version != 1 {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "unsupported_schema",
            "schemaVersion must be 1",
        ));
    }
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|x| x.to_str().ok())
        .and_then(|x| x.strip_prefix("Bearer "))
        .filter(|x| !x.is_empty())
        .ok_or_else(|| {
            err(
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                "device bearer token required",
            )
        })?;
    let token_hash = Sha256::digest(token.as_bytes()).to_vec();
    let mut tx = s.db.begin().await.map_err(db_err)?;
    let device=sqlx::query("SELECT vehicle_id FROM devices WHERE id=$1 AND token_hash=$2 AND token_revoked_at IS NULL FOR SHARE").bind(batch.device_id).bind(&token_hash).fetch_optional(&mut *tx).await.map_err(db_err)?.ok_or_else(||err(StatusCode::UNAUTHORIZED,"unauthorized","invalid device token"))?;
    let vehicle_id: Uuid = device
        .try_get::<Option<Uuid>, _>("vehicle_id")
        .map_err(db_err)?
        .ok_or_else(|| {
            err(
                StatusCode::CONFLICT,
                "device_unassigned",
                "device has no current vehicle",
            )
        })?;
    let raw = serde_json::to_value(&batch).map_err(|_| {
        err(
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "could not encode payload",
        )
    })?;
    let payload_hash = Sha256::digest(serde_json::to_vec(&canonical(raw)).unwrap()).to_vec();
    let lock_key = format!("{}:{}", batch.device_id, batch.batch_id);
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind(lock_key)
        .execute(&mut *tx)
        .await
        .map_err(db_err)?;
    if let Some(row)=sqlx::query("SELECT payload_sha256,gps_count,obd_count,device_count,accepted_at FROM ingest_batches WHERE device_id=$1 AND batch_id=$2").bind(batch.device_id).bind(batch.batch_id).fetch_optional(&mut *tx).await.map_err(db_err)? {
  let prior:Vec<u8>=row.try_get("payload_sha256").map_err(db_err)?;
  if prior!=payload_hash { return Err(err(StatusCode::CONFLICT,"batch_id_conflict","batchId was already accepted with a different payload")); }
  let ack=Ack{batch_id:batch.batch_id,accepted:true,gps_accepted:row.try_get("gps_count").map_err(db_err)?,obd_accepted:row.try_get("obd_count").map_err(db_err)?,device_accepted:row.try_get("device_count").map_err(db_err)?,received_at:row.try_get("accepted_at").map_err(db_err)?,schema_version:1};
  tx.commit().await.map_err(db_err)?; return Ok(Json(ack));
 }
    let n = batch.gps_samples.len() + batch.obd_samples.len() + batch.device_samples.len();
    if n > 10_000
        || batch.gps_samples.len() > 10_000
        || batch.obd_samples.len() > 10_000
        || batch.device_samples.len() > 10_000
    {
        return Err(err(
            StatusCode::PAYLOAD_TOO_LARGE,
            "batch_too_large",
            "maximum 10000 samples per batch",
        ));
    }
    let mut sample_ids = HashSet::with_capacity(n);
    let unique = batch
        .gps_samples
        .iter()
        .map(|x| x.sample_id)
        .chain(batch.obd_samples.iter().map(|x| x.sample_id))
        .chain(batch.device_samples.iter().map(|x| x.sample_id))
        .all(|id| sample_ids.insert(id));
    if !unique {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "duplicate_sample_id",
            "sampleId must be unique within a batch",
        ));
    }
    if batch.trip.start_reason.trim().is_empty()
        || batch.trip.start_reason.len() > 80
        || batch.trip.end_reason.as_ref().is_some_and(|x| x.len() > 80)
        || batch
            .trip
            .ended_at
            .is_some_and(|x| x < batch.trip.started_at)
        || [batch.trip.distance_gps_m, batch.trip.distance_obd_m]
            .into_iter()
            .flatten()
            .any(|x| !x.is_finite() || x < 0.0)
    {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "invalid_trip",
            "invalid trip metadata",
        ));
    }
    for p in &batch.gps_samples {
        if !(-90.0..=90.0).contains(&p.latitude)
            || !(-180.0..=180.0).contains(&p.longitude)
            || [p.altitude_m].into_iter().flatten().any(|x| !x.is_finite())
            || [p.speed_mps, p.bearing_deg, p.horizontal_accuracy_m]
                .into_iter()
                .flatten()
                .any(|x| !x.is_finite() || x < 0.0)
        {
            return Err(err(
                StatusCode::BAD_REQUEST,
                "invalid_sample",
                "invalid GPS sample",
            ));
        }
    }
    for p in &batch.obd_samples {
        if p.pid.is_empty()
            || p.pid.len() > 32
            || !p
                .pid
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'-'))
            || !p.value.is_finite()
            || p.name
                .as_ref()
                .is_some_and(|x| x.trim().is_empty() || x.len() > 128)
            || p.unit.as_ref().is_some_and(|x| x.len() > 32)
        {
            return Err(err(
                StatusCode::BAD_REQUEST,
                "invalid_sample",
                "invalid OBD sample",
            ));
        }
    }
    for p in &batch.device_samples {
        if p.battery_pct
            .is_some_and(|x| !x.is_finite() || !(0.0..=100.0).contains(&x))
            || p.battery_temp_c.is_some_and(|x| !x.is_finite())
        {
            return Err(err(
                StatusCode::BAD_REQUEST,
                "invalid_sample",
                "invalid device sample",
            ));
        }
    }
    let assignment=sqlx::query("SELECT 1 FROM device_vehicle_assignments WHERE device_id=$1 AND vehicle_id=$2 AND unassigned_at IS NULL").bind(batch.device_id).bind(vehicle_id).fetch_optional(&mut *tx).await.map_err(db_err)?;
    if assignment.is_none() {
        return Err(err(
            StatusCode::CONFLICT,
            "assignment_missing",
            "device has no matching active vehicle assignment",
        ));
    }
    sqlx::query("INSERT INTO trips(id,device_id,vehicle_id,started_at,ended_at,start_reason,end_reason,distance_gps_m,distance_obd_m) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT(id) DO NOTHING").bind(batch.trip.id).bind(batch.device_id).bind(vehicle_id).bind(batch.trip.started_at).bind(batch.trip.ended_at).bind(&batch.trip.start_reason).bind(&batch.trip.end_reason).bind(batch.trip.distance_gps_m).bind(batch.trip.distance_obd_m).execute(&mut *tx).await.map_err(db_err)?;
    let trip_owner = sqlx::query("SELECT device_id, vehicle_id FROM trips WHERE id=$1")
        .bind(batch.trip.id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(db_err)?;
    if trip_owner.as_ref().is_none_or(|r| {
        r.try_get::<Uuid, _>("device_id").ok() != Some(batch.device_id)
            || r.try_get::<Uuid, _>("vehicle_id").ok() != Some(vehicle_id)
    }) {
        return Err(err(
            StatusCode::CONFLICT,
            "trip_conflict",
            "trip id belongs to another device",
        ));
    }
    sqlx::query("UPDATE trips SET ended_at=COALESCE($2,ended_at), end_reason=COALESCE($3,end_reason), distance_gps_m=COALESCE($4,distance_gps_m), distance_obd_m=COALESCE($5,distance_obd_m) WHERE id=$1")
        .bind(batch.trip.id)
        .bind(batch.trip.ended_at)
        .bind(&batch.trip.end_reason)
        .bind(batch.trip.distance_gps_m)
        .bind(batch.trip.distance_obd_m)
        .execute(&mut *tx).await.map_err(db_err)?;
    if !batch.gps_samples.is_empty() {
        // Different batches for one trip can arrive out of order. Serialize their
        // inserts and delta repair so the predecessor/successor stays consistent.
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 1))")
            .bind(batch.trip.id.to_string())
            .execute(&mut *tx)
            .await
            .map_err(db_err)?;
    }
    for p in &batch.gps_samples {
        let inserted = sqlx::query("INSERT INTO gps_samples(device_id,observed_at,sample_id,trip_id,latitude,longitude,altitude_m,speed_mps,bearing_deg,horizontal_accuracy_m) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT DO NOTHING").bind(batch.device_id).bind(p.observed_at).bind(p.sample_id).bind(batch.trip.id).bind(p.latitude).bind(p.longitude).bind(p.altitude_m).bind(p.speed_mps).bind(p.bearing_deg).bind(p.horizontal_accuracy_m).execute(&mut *tx).await.map_err(db_err)?;
        if inserted.rows_affected() != 1 {
            return Err(err(
                StatusCode::CONFLICT,
                "sample_conflict",
                "sampleId was already accepted in another batch",
            ));
        }
        for (name, value) in [
            ("gps.speed_mps", p.speed_mps.map(f64::from)),
            ("gps.altitude_m", p.altitude_m),
            ("gps.accuracy_m", p.horizontal_accuracy_m.map(f64::from)),
        ] {
            if let Some(value) = value {
                insert_metric_sample(
                    &mut tx,
                    name,
                    batch.device_id,
                    vehicle_id,
                    batch.trip.id,
                    p.observed_at,
                    p.sample_id,
                    value,
                    "",
                    None,
                )
                .await?;
            }
        }
    }
    if let (Some(first), Some(last)) = (
        batch.gps_samples.iter().map(|p| p.observed_at).min(),
        batch.gps_samples.iter().map(|p| p.observed_at).max(),
    ) {
        refresh_gps_deltas(&mut tx, batch.trip.id, first, last).await?;
    }
    for p in &batch.obd_samples {
        let inserted = sqlx::query("INSERT INTO obd_measurements(device_id,observed_at,sample_id,trip_id,pid,value_numeric) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT DO NOTHING").bind(batch.device_id).bind(p.observed_at).bind(p.sample_id).bind(batch.trip.id).bind(&p.pid).bind(p.value).execute(&mut *tx).await.map_err(db_err)?;
        if inserted.rows_affected() != 1 {
            return Err(err(
                StatusCode::CONFLICT,
                "sample_conflict",
                "sampleId was already accepted in another batch",
            ));
        }
        let metric_name = if p.pid.starts_with("calc.") {
            format!("obd.{}", p.pid.to_ascii_lowercase())
        } else {
            format!("obd.pid.{}", p.pid.to_ascii_lowercase())
        };
        insert_metric_sample(
            &mut tx,
            &metric_name,
            batch.device_id,
            vehicle_id,
            batch.trip.id,
            p.observed_at,
            p.sample_id,
            p.value,
            p.unit.as_deref().unwrap_or(""),
            p.name.as_deref(),
        )
        .await?;
    }
    for p in &batch.device_samples {
        let inserted = sqlx::query("INSERT INTO device_samples(device_id,observed_at,sample_id,trip_id,power_connected,battery_pct,battery_temp_c) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT DO NOTHING").bind(batch.device_id).bind(p.observed_at).bind(p.sample_id).bind(p.trip_id.or(Some(batch.trip.id))).bind(p.power_connected).bind(p.battery_pct).bind(p.battery_temp_c).execute(&mut *tx).await.map_err(db_err)?;
        if inserted.rows_affected() != 1 {
            return Err(err(
                StatusCode::CONFLICT,
                "sample_conflict",
                "sampleId was already accepted in another batch",
            ));
        }
        let trip_id = p.trip_id.unwrap_or(batch.trip.id);
        for (name, value) in [
            (
                "device.power_connected",
                p.power_connected.map(|v| if v { 1.0 } else { 0.0 }),
            ),
            ("device.battery_pct", p.battery_pct.map(f64::from)),
            ("device.battery_temp_c", p.battery_temp_c.map(f64::from)),
        ] {
            if let Some(value) = value {
                insert_metric_sample(
                    &mut tx,
                    name,
                    batch.device_id,
                    vehicle_id,
                    trip_id,
                    p.observed_at,
                    p.sample_id,
                    value,
                    "",
                    None,
                )
                .await?;
            }
        }
    }
    let row=sqlx::query("INSERT INTO ingest_batches(device_id,batch_id,payload_sha256,gps_count,obd_count,device_count) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(device_id,batch_id) DO NOTHING RETURNING accepted_at").bind(batch.device_id).bind(batch.batch_id).bind(&payload_hash).bind(batch.gps_samples.len() as i32).bind(batch.obd_samples.len() as i32).bind(batch.device_samples.len() as i32).fetch_optional(&mut *tx).await.map_err(db_err)?;
    let ack = if let Some(row) = row {
        Ack {
            batch_id: batch.batch_id,
            accepted: true,
            gps_accepted: batch.gps_samples.len() as i32,
            obd_accepted: batch.obd_samples.len() as i32,
            device_accepted: batch.device_samples.len() as i32,
            received_at: row.try_get("accepted_at").map_err(db_err)?,
            schema_version: 1,
        }
    } else {
        let r=sqlx::query("SELECT payload_sha256,gps_count,obd_count,device_count,accepted_at FROM ingest_batches WHERE device_id=$1 AND batch_id=$2").bind(batch.device_id).bind(batch.batch_id).fetch_one(&mut *tx).await.map_err(db_err)?;
        let h: Vec<u8> = r.try_get("payload_sha256").map_err(db_err)?;
        if h != payload_hash {
            return Err(err(
                StatusCode::CONFLICT,
                "batch_id_conflict",
                "batchId was accepted concurrently with a different payload",
            ));
        }
        Ack {
            batch_id: batch.batch_id,
            accepted: true,
            gps_accepted: r.try_get("gps_count").map_err(db_err)?,
            obd_accepted: r.try_get("obd_count").map_err(db_err)?,
            device_accepted: r.try_get("device_count").map_err(db_err)?,
            received_at: r.try_get("accepted_at").map_err(db_err)?,
            schema_version: 1,
        }
    };
    tx.commit().await.map_err(db_err)?;
    Ok(Json(ack))
}
async fn refresh_gps_deltas(
    tx: &mut Transaction<'_, Postgres>,
    trip_id: Uuid,
    first: DateTime<Utc>,
    last: DateTime<Utc>,
) -> Result<()> {
    sqlx::query(
        "WITH bounds AS (
            SELECT COALESCE((SELECT observed_at FROM gps_samples
                             WHERE trip_id=$1 AND observed_at < $2
                             ORDER BY observed_at DESC LIMIT 1), $2) AS lo,
                   COALESCE((SELECT observed_at FROM gps_samples
                             WHERE trip_id=$1 AND observed_at > $3
                             ORDER BY observed_at LIMIT 1), $3) AS hi
         ), ordered AS (
            SELECT g.device_id, g.observed_at, g.sample_id,
                   EXTRACT(EPOCH FROM g.observed_at - LAG(g.observed_at) OVER w)::double precision AS elapsed_sec,
                   ST_Distance(g.position, LAG(g.position) OVER w) AS traveled_m
            FROM gps_samples g CROSS JOIN bounds b
            WHERE g.trip_id=$1 AND g.observed_at BETWEEN b.lo AND b.hi
            WINDOW w AS (ORDER BY g.observed_at, g.sample_id)
         )
         UPDATE gps_samples g
         SET delta_sec=o.elapsed_sec, delta_m=o.traveled_m
         FROM ordered o
         WHERE g.device_id=o.device_id AND g.observed_at=o.observed_at AND g.sample_id=o.sample_id
           AND g.trip_id=$1 AND g.observed_at >= $2",
    )
    .bind(trip_id)
    .bind(first)
    .bind(last)
    .execute(&mut **tx)
    .await
    .map_err(db_err)?;
    sqlx::query(
        "UPDATE trips SET distance_gps_m=(SELECT COALESCE(SUM(delta_m), 0)
          FROM gps_samples WHERE trip_id=$1) WHERE id=$1",
    )
    .bind(trip_id)
    .execute(&mut **tx)
    .await
    .map_err(db_err)?;
    Ok(())
}

async fn insert_metric_sample(
    tx: &mut Transaction<'_, Postgres>,
    name: &str,
    device_id: Uuid,
    vehicle_id: Uuid,
    trip_id: Uuid,
    observed_at: DateTime<Utc>,
    sample_id: Uuid,
    value: f64,
    unit: &str,
    description: Option<&str>,
) -> Result<()> {
    let written = sqlx::query(
        "WITH definition AS (
            INSERT INTO metric_definitions(name, source, unit, description)
            VALUES ($1, split_part($1, '.', 1), $8, $9)
            ON CONFLICT(name) DO UPDATE SET
                unit=CASE WHEN metric_definitions.unit='' THEN EXCLUDED.unit ELSE metric_definitions.unit END,
                description=COALESCE(metric_definitions.description, EXCLUDED.description)
            RETURNING id
         ), series AS (
            INSERT INTO metric_series(metric_id, device_id, vehicle_id, trip_id, labels)
            SELECT id, $2, $3, $4, '{}'::jsonb FROM definition
            ON CONFLICT(metric_id, device_id, vehicle_id, trip_id, labels)
            DO UPDATE SET id=metric_series.id RETURNING id
         )
         INSERT INTO metric_samples(series_id, observed_at, sample_id, value_numeric)
         SELECT id, $5, $6, $7 FROM series ON CONFLICT DO NOTHING",
    )
    .bind(name)
    .bind(device_id)
    .bind(vehicle_id)
    .bind(trip_id)
    .bind(observed_at)
    .bind(sample_id)
    .bind(value)
    .bind(unit)
    .bind(description)
    .execute(&mut **tx)
    .await
    .map_err(db_err)?;
    if written.rows_affected() != 1 {
        return Err(err(
            StatusCode::CONFLICT,
            "sample_conflict",
            "metric sample was already accepted in another batch",
        ));
    }
    Ok(())
}
fn db_err(_: sqlx::Error) -> HttpError {
    err(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "database operation failed",
    )
}
const SESSION_SECONDS: i64 = 12 * 60 * 60;
const SESSION_COOKIE: &str = "opnord_session";
static DUMMY_HASH: OnceLock<String> = OnceLock::new();

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LoginBody {
    username: String,
    password: String,
}
#[derive(Serialize)]
struct AuthView {
    username: String,
}

fn session_hash(headers: &HeaderMap) -> Option<Vec<u8>> {
    let raw = headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|h| h.to_str().ok())
        .flat_map(|h| h.split(';'))
        .find_map(|part| part.trim().strip_prefix(&format!("{SESSION_COOKIE}=")))?;
    let token = hex::decode(raw).ok()?;
    if token.len() != 32 {
        return None;
    }
    Some(Sha256::digest(token).to_vec())
}

fn cookie_header(token: Option<&str>, secure: bool) -> Result<HeaderValue> {
    let value = match token {
        Some(token) => format!("{SESSION_COOKIE}={token}; Path=/api; HttpOnly; SameSite=Strict; Max-Age={SESSION_SECONDS}{}", if secure { "; Secure" } else { "" }),
        None => format!("{SESSION_COOKIE}=; Path=/api; HttpOnly; SameSite=Strict; Max-Age=0{}", if secure { "; Secure" } else { "" }),
    };
    HeaderValue::from_str(&value).map_err(|_| {
        err(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "invalid cookie",
        )
    })
}

async fn dashboard(db: &PgPool, headers: &HeaderMap) -> Result<AuthView> {
    let hash = session_hash(headers)
        .ok_or_else(|| err(StatusCode::UNAUTHORIZED, "unauthorized", "login required"))?;
    let row = sqlx::query("SELECT u.username FROM dashboard_sessions s JOIN dashboard_users u ON u.id=s.user_id WHERE s.session_hash=$1 AND s.expires_at>now() AND u.disabled_at IS NULL")
        .bind(hash).fetch_optional(db).await.map_err(db_err)?
        .ok_or_else(|| err(StatusCode::UNAUTHORIZED, "unauthorized", "login required"))?;
    Ok(AuthView {
        username: row.try_get("username").map_err(db_err)?,
    })
}

async fn auth_session(State(s): State<App>, headers: HeaderMap) -> Result<Json<AuthView>> {
    Ok(Json(dashboard(&s.db, &headers).await?))
}

async fn login(State(s): State<App>, Json(body): Json<LoginBody>) -> Result<Response> {
    let username = body.username.trim().to_ascii_lowercase();
    if username.is_empty()
        || username.len() > 64
        || body.password.is_empty()
        || body.password.len() > 1024
    {
        return Err(err(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "invalid username or password",
        ));
    }
    let row = sqlx::query(
        "SELECT id,password_hash FROM dashboard_users WHERE username=$1 AND disabled_at IS NULL",
    )
    .bind(&username)
    .fetch_optional(&s.db)
    .await
    .map_err(db_err)?;
    let user_id: Option<Uuid> = row
        .as_ref()
        .map(|r| r.try_get("id"))
        .transpose()
        .map_err(db_err)?;
    let encoded: String = if let Some(r) = row {
        r.try_get("password_hash").map_err(db_err)?
    } else {
        DUMMY_HASH
            .get_or_init(|| hash_password("invalid-account-dummy").expect("Argon2 parameters"))
            .clone()
    };
    let password_ok =
        tokio::task::spawn_blocking(move || verify_password(&body.password, &encoded))
            .await
            .map_err(|_| {
                err(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    "password verification failed",
                )
            })?;
    let Some(user_id) = user_id.filter(|_| password_ok) else {
        return Err(err(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "invalid username or password",
        ));
    };
    let mut raw = [0u8; 32];
    OsRng.fill_bytes(&mut raw);
    let token = hex::encode(raw);
    let hash = Sha256::digest(raw).to_vec();
    sqlx::query("INSERT INTO dashboard_sessions(session_hash,user_id,expires_at) VALUES($1,$2,now()+interval '12 hours')")
        .bind(hash).bind(user_id).execute(&s.db).await.map_err(db_err)?;
    let mut response = Json(AuthView { username }).into_response();
    response.headers_mut().insert(
        header::SET_COOKIE,
        cookie_header(Some(&token), s.cookie_secure)?,
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(response)
}

async fn logout(State(s): State<App>, headers: HeaderMap) -> Result<Response> {
    if let Some(hash) = session_hash(&headers) {
        sqlx::query("DELETE FROM dashboard_sessions WHERE session_hash=$1")
            .bind(hash)
            .execute(&s.db)
            .await
            .map_err(db_err)?;
    }
    let mut response = StatusCode::NO_CONTENT.into_response();
    response
        .headers_mut()
        .insert(header::SET_COOKIE, cookie_header(None, s.cookie_secure)?);
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(response)
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Vehicle {
    id: Uuid,
    display_name: String,
    make: Option<String>,
    model: Option<String>,
    model_year: Option<i16>,
}
async fn vehicles(State(s): State<App>, headers: HeaderMap) -> Result<Json<Vec<Vehicle>>> {
    dashboard(&s.db, &headers).await?;
    let rows = sqlx::query(
        "SELECT id,display_name,make,model,model_year FROM vehicles ORDER BY display_name",
    )
    .fetch_all(&s.db)
    .await
    .map_err(db_err)?;
    let mut out = Vec::new();
    for r in rows {
        out.push(Vehicle {
            id: r.try_get("id").map_err(db_err)?,
            display_name: r.try_get("display_name").map_err(db_err)?,
            make: r.try_get("make").map_err(db_err)?,
            model: r.try_get("model").map_err(db_err)?,
            model_year: r.try_get("model_year").map_err(db_err)?,
        });
    }
    Ok(Json(out))
}
#[derive(Deserialize)]
struct TripParams {
    limit: Option<i64>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TripSummary {
    id: Uuid,
    started_at: DateTime<Utc>,
    ended_at: Option<DateTime<Utc>>,
    start_reason: String,
    end_reason: Option<String>,
    distance_gps_m: Option<f64>,
    distance_obd_m: Option<f64>,
}
async fn trips(
    State(s): State<App>,
    headers: HeaderMap,
    Path(vehicle): Path<Uuid>,
    Query(q): Query<TripParams>,
) -> Result<Json<Vec<TripSummary>>> {
    dashboard(&s.db, &headers).await?;
    let limit = q.limit.unwrap_or(100).clamp(1, 500);
    let rows=sqlx::query("SELECT id,started_at,ended_at,start_reason,end_reason,distance_gps_m,distance_obd_m FROM trips WHERE vehicle_id=$1 ORDER BY started_at DESC LIMIT $2").bind(vehicle).bind(limit).fetch_all(&s.db).await.map_err(db_err)?;
    let mut out = Vec::new();
    for r in rows {
        out.push(TripSummary {
            id: r.try_get("id").map_err(db_err)?,
            started_at: r.try_get("started_at").map_err(db_err)?,
            ended_at: r.try_get("ended_at").map_err(db_err)?,
            start_reason: r.try_get("start_reason").map_err(db_err)?,
            end_reason: r.try_get("end_reason").map_err(db_err)?,
            distance_gps_m: r.try_get("distance_gps_m").map_err(db_err)?,
            distance_obd_m: r.try_get("distance_obd_m").map_err(db_err)?,
        });
    }
    Ok(Json(out))
}
#[derive(Deserialize)]
struct GpsParams {
    limit: Option<i64>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GpsOut {
    sample_id: Uuid,
    observed_at: DateTime<Utc>,
    delta_sec: Option<f64>,
    delta_m: Option<f64>,
    latitude: f64,
    longitude: f64,
    altitude_m: Option<f64>,
    speed_mps: Option<f32>,
    bearing_deg: Option<f32>,
    horizontal_accuracy_m: Option<f32>,
}
async fn gps(
    State(s): State<App>,
    headers: HeaderMap,
    Path(trip): Path<Uuid>,
    Query(q): Query<GpsParams>,
) -> Result<Json<Vec<GpsOut>>> {
    dashboard(&s.db, &headers).await?;
    let limit = q.limit.unwrap_or(5000).clamp(1, 10000);
    let rows=sqlx::query("SELECT sample_id,observed_at,delta_sec,delta_m,latitude,longitude,altitude_m,speed_mps,bearing_deg,horizontal_accuracy_m FROM gps_samples WHERE trip_id=$1 ORDER BY observed_at,sample_id LIMIT $2").bind(trip).bind(limit).fetch_all(&s.db).await.map_err(db_err)?;
    let mut out = Vec::new();
    for r in rows {
        out.push(GpsOut {
            sample_id: r.try_get("sample_id").map_err(db_err)?,
            observed_at: r.try_get("observed_at").map_err(db_err)?,
            delta_sec: r.try_get("delta_sec").map_err(db_err)?,
            delta_m: r.try_get("delta_m").map_err(db_err)?,
            latitude: r.try_get("latitude").map_err(db_err)?,
            longitude: r.try_get("longitude").map_err(db_err)?,
            altitude_m: r.try_get("altitude_m").map_err(db_err)?,
            speed_mps: r.try_get("speed_mps").map_err(db_err)?,
            bearing_deg: r.try_get("bearing_deg").map_err(db_err)?,
            horizontal_accuracy_m: r.try_get("horizontal_accuracy_m").map_err(db_err)?,
        });
    }
    Ok(Json(out))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MetricParams {
    name: String,
    from: DateTime<Utc>,
    to: DateTime<Utc>,
    limit: Option<i64>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricOut {
    sample_id: Uuid,
    observed_at: DateTime<Utc>,
    name: String,
    unit: String,
    value: f64,
}
async fn metrics(
    State(s): State<App>,
    headers: HeaderMap,
    Path(trip): Path<Uuid>,
    Query(q): Query<MetricParams>,
) -> Result<Json<Vec<MetricOut>>> {
    dashboard(&s.db, &headers).await?;
    if q.from >= q.to || q.name.is_empty() || q.name.len() > 96 {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "invalid_query",
            "invalid metric or time range",
        ));
    }
    let limit = q.limit.unwrap_or(1000).clamp(1, 5000);
    let rows = sqlx::query("SELECT m.sample_id,m.observed_at,d.name,d.unit,m.value_numeric FROM metric_series s JOIN metric_definitions d ON d.id=s.metric_id JOIN metric_samples m ON m.series_id=s.id WHERE s.trip_id=$1 AND d.name=$2 AND m.observed_at >= $3 AND m.observed_at < $4 ORDER BY m.observed_at LIMIT $5")
        .bind(trip).bind(&q.name).bind(q.from).bind(q.to).bind(limit)
        .fetch_all(&s.db).await.map_err(db_err)?;
    let mut out = Vec::with_capacity(rows.len());
    for r in rows {
        out.push(MetricOut {
            sample_id: r.try_get("sample_id").map_err(db_err)?,
            observed_at: r.try_get("observed_at").map_err(db_err)?,
            name: r.try_get("name").map_err(db_err)?,
            unit: r.try_get("unit").map_err(db_err)?,
            value: r.try_get("value_numeric").map_err(db_err)?,
        });
    }
    Ok(Json(out))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricCatalogEntry {
    name: String,
    unit: String,
    description: Option<String>,
}

async fn metric_catalog(
    State(s): State<App>,
    headers: HeaderMap,
    Path(trip): Path<Uuid>,
) -> Result<Json<Vec<MetricCatalogEntry>>> {
    dashboard(&s.db, &headers).await?;
    let rows = sqlx::query(
        "SELECT DISTINCT d.name, d.unit, d.description
         FROM metric_series s JOIN metric_definitions d ON d.id=s.metric_id
         WHERE s.trip_id=$1 ORDER BY d.name",
    )
    .bind(trip)
    .fetch_all(&s.db)
    .await
    .map_err(db_err)?;
    let mut out = Vec::with_capacity(rows.len());
    for r in rows {
        out.push(MetricCatalogEntry {
            name: r.try_get("name").map_err(db_err)?,
            unit: r.try_get("unit").map_err(db_err)?,
            description: r.try_get("description").map_err(db_err)?,
        });
    }
    Ok(Json(out))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GeoParams {
    vehicle_id: Uuid,
    name: String,
    from: DateTime<Utc>,
    to: DateTime<Utc>,
    longitude: f64,
    latitude: f64,
    radius_m: f64,
    tolerance_ms: Option<i64>,
    limit: Option<i64>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GeoOut {
    trip_id: Uuid,
    gps_at: DateTime<Utc>,
    latitude: f64,
    longitude: f64,
    metric_at: DateTime<Utc>,
    value: f64,
    unit: String,
}
async fn geo_correlations(
    State(s): State<App>,
    headers: HeaderMap,
    Query(q): Query<GeoParams>,
) -> Result<Json<Vec<GeoOut>>> {
    dashboard(&s.db, &headers).await?;
    if q.from >= q.to
        || q.name.is_empty()
        || q.name.len() > 96
        || !(-180.0..=180.0).contains(&q.longitude)
        || !(-90.0..=90.0).contains(&q.latitude)
        || !q.radius_m.is_finite()
        || !(1.0..=10_000.0).contains(&q.radius_m)
        || q.tolerance_ms.unwrap_or(2000) < 0
        || q.tolerance_ms.unwrap_or(2000) > 30_000
    {
        return Err(err(
            StatusCode::BAD_REQUEST,
            "invalid_query",
            "invalid geospatial query",
        ));
    }
    let limit = q.limit.unwrap_or(500).clamp(1, 1000);
    let rows = sqlx::query(
        "WITH fixes AS MATERIALIZED (
            SELECT g.trip_id,g.observed_at,g.latitude,g.longitude
            FROM gps_samples g JOIN trips t ON t.id=g.trip_id
            WHERE t.vehicle_id=$1 AND g.observed_at >= $2 AND g.observed_at < $3
              AND ST_DWithin(g.position, ST_SetSRID(ST_MakePoint($4,$5),4326)::geography, $6)
            ORDER BY g.observed_at LIMIT $7
         )
         SELECT f.trip_id,f.observed_at AS gps_at,f.latitude,f.longitude,
                nearest.observed_at AS metric_at,nearest.value_numeric,d.unit
         FROM fixes f
         JOIN metric_series s ON s.trip_id=f.trip_id
         JOIN metric_definitions d ON d.id=s.metric_id AND d.name=$8
         JOIN LATERAL (
            SELECT m.observed_at,m.value_numeric FROM metric_samples m
            WHERE m.series_id=s.id
              AND m.observed_at BETWEEN f.observed_at - ($9::bigint * interval '1 millisecond')
                                    AND f.observed_at + ($9::bigint * interval '1 millisecond')
            ORDER BY abs(extract(epoch FROM m.observed_at-f.observed_at)) LIMIT 1
         ) nearest ON true ORDER BY f.observed_at LIMIT $7",
    )
    .bind(q.vehicle_id)
    .bind(q.from)
    .bind(q.to)
    .bind(q.longitude)
    .bind(q.latitude)
    .bind(q.radius_m)
    .bind(limit)
    .bind(&q.name)
    .bind(q.tolerance_ms.unwrap_or(2000))
    .fetch_all(&s.db)
    .await
    .map_err(db_err)?;
    let mut out = Vec::with_capacity(rows.len());
    for r in rows {
        out.push(GeoOut {
            trip_id: r.try_get("trip_id").map_err(db_err)?,
            gps_at: r.try_get("gps_at").map_err(db_err)?,
            latitude: r.try_get("latitude").map_err(db_err)?,
            longitude: r.try_get("longitude").map_err(db_err)?,
            metric_at: r.try_get("metric_at").map_err(db_err)?,
            value: r.try_get("value_numeric").map_err(db_err)?,
            unit: r.try_get("unit").map_err(db_err)?,
        });
    }
    Ok(Json(out))
}

#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let db_url = std::env::var("DATABASE_URL")?;
    let addr: SocketAddr = std::env::var("BIND_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:8080".into())
        .parse()?;
    let cookie_secure = std::env::var("DASHBOARD_COOKIE_SECURE")
        .unwrap_or_else(|_| "true".into())
        .parse::<bool>()?;
    let local_http_container = std::env::var("DASHBOARD_LOCAL_HTTP_CONTAINER")
        .unwrap_or_else(|_| "false".into())
        .parse::<bool>()?;
    if !cookie_secure && !addr.ip().is_loopback() && !local_http_container {
        return Err(
            "DASHBOARD_COOKIE_SECURE=false requires loopback BIND_ADDR or DASHBOARD_LOCAL_HTTP_CONTAINER=true"
                .into(),
        );
    }
    let db = PgPoolOptions::new()
        .max_connections(10)
        .connect(&db_url)
        .await?;
    sqlx::migrate!().run(&db).await?;
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/readyz", get(ready))
        .route("/api/v1/telemetry/batches", post(ingest))
        .route("/api/v1/auth/login", post(login))
        .route("/api/v1/auth/session", get(auth_session))
        .route("/api/v1/auth/logout", post(logout))
        .route("/api/v1/vehicles", get(vehicles))
        .route("/api/v1/vehicles/:vehicle_id/trips", get(trips))
        .route("/api/v1/trips/:trip_id/gps", get(gps))
        .route("/api/v1/trips/:trip_id/metrics", get(metrics))
        .route("/api/v1/trips/:trip_id/metric-catalog", get(metric_catalog))
        .route("/api/v1/geo/correlations", get(geo_correlations))
        .with_state(App { db, cookie_secure })
        .layer(axum::extract::DefaultBodyLimit::max(2 * 1024 * 1024));
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

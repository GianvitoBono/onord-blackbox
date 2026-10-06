use opnord_server::device_token::short_token;
use rand::{rngs::OsRng, RngCore};
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use sqlx::postgres::PgPoolOptions;
use std::{
    env,
    fs::{self, OpenOptions},
    io::Write,
    os::unix::fs::OpenOptionsExt,
    path::PathBuf,
};
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExistingCredentials {
    vehicle_id: Uuid,
    device_id: Uuid,
    device_token: String,
}

fn write_pending(
    output: &PathBuf,
    vehicle_id: Uuid,
    device_id: Uuid,
    token: &str,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    let pending = output.with_extension("pending");
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&pending)?;
    serde_json::to_writer_pretty(
        &mut file,
        &json!({
            "vehicleId": vehicle_id,
            "deviceId": device_id,
            "deviceToken": token,
        }),
    )?;
    writeln!(file)?;
    file.sync_all()?;
    Ok(pending)
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = env::args().skip(1).collect();
    if args.len() > 1 || (args.len() == 1 && args[0] != "--rotate") {
        return Err("usage: provision_device [--rotate]".into());
    }
    let rotate = !args.is_empty();
    let vehicle_name = env::var("VEHICLE_NAME").unwrap_or_else(|_| "My car".into());
    let device_name = env::var("DEVICE_NAME").unwrap_or_else(|_| "OnePlus Nord".into());
    if vehicle_name.trim().is_empty()
        || vehicle_name.len() > 120
        || device_name.trim().is_empty()
        || device_name.len() > 120
    {
        return Err("VEHICLE_NAME and DEVICE_NAME must be 1-120 characters".into());
    }
    let output = PathBuf::from(
        env::var("DEVICE_CREDENTIALS_FILE")
            .unwrap_or_else(|_| ".data/device-credentials.json".into()),
    );
    if rotate {
        let current: ExistingCredentials = serde_json::from_slice(&fs::read(&output)?)?;
        let old_hash = Sha256::digest(current.device_token.as_bytes()).to_vec();
        let token = short_token();
        let new_hash = Sha256::digest(token.as_bytes()).to_vec();
        let pool = PgPoolOptions::new()
            .max_connections(2)
            .connect(&env::var("DATABASE_URL")?)
            .await?;
        let mut tx = pool.begin().await?;
        let changed = sqlx::query(
            "UPDATE devices SET token_hash=$4
             WHERE id=$1 AND vehicle_id=$2 AND token_hash=$3 AND token_revoked_at IS NULL",
        )
        .bind(current.device_id)
        .bind(current.vehicle_id)
        .bind(old_hash)
        .bind(new_hash)
        .execute(&mut *tx)
        .await?;
        if changed.rows_affected() != 1 {
            return Err("device credentials file does not match an active database token".into());
        }
        let pending = write_pending(&output, current.vehicle_id, current.device_id, &token)?;
        if let Err(error) = tx.commit().await {
            let _ = fs::remove_file(&pending);
            return Err(error.into());
        }
        fs::rename(&pending, &output).map_err(|error| {
            format!(
                "database rotated, but could not replace credentials: {error}; new token is in {}",
                pending.display()
            )
        })?;
        println!(
            "Device token rotated; credentials saved to {}",
            output.display()
        );
        return Ok(());
    }
    if output.exists() {
        return Err(format!("credentials already exist: {}", output.display()).into());
    }
    let mut raw = [0u8; 32];
    OsRng.fill_bytes(&mut raw);
    let token = hex::encode(raw);
    let token_hash = Sha256::digest(token.as_bytes()).to_vec();
    let vehicle_id = Uuid::new_v4();
    let device_id = Uuid::new_v4();
    let pending = write_pending(&output, vehicle_id, device_id, &token)?;

    let pool = PgPoolOptions::new()
        .max_connections(2)
        .connect(&env::var("DATABASE_URL")?)
        .await?;
    let mut tx = pool.begin().await?;
    sqlx::query("INSERT INTO vehicles(id, display_name) VALUES($1, $2)")
        .bind(vehicle_id)
        .bind(vehicle_name)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO devices(id, vehicle_id, display_name, token_hash) VALUES($1, $2, $3, $4)",
    )
    .bind(device_id)
    .bind(vehicle_id)
    .bind(device_name)
    .bind(token_hash)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO device_vehicle_assignments(device_id, vehicle_id, assigned_at)
         VALUES($1, $2, now())",
    )
    .bind(device_id)
    .bind(vehicle_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    fs::rename(pending, &output)?;
    println!("Device ready; credentials saved to {}", output.display());
    Ok(())
}

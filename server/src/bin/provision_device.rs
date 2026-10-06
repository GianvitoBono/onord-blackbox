use rand::{rngs::OsRng, RngCore};
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

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
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
    if output.exists() {
        return Err(format!("credentials already exist: {}", output.display()).into());
    }
    let mut raw = [0u8; 32];
    OsRng.fill_bytes(&mut raw);
    let token = hex::encode(raw);
    let token_hash = Sha256::digest(token.as_bytes()).to_vec();
    let vehicle_id = Uuid::new_v4();
    let device_id = Uuid::new_v4();
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

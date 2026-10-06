use opnord_server::password::hash_password;
use rand::{rngs::OsRng, RngCore};
use sqlx::{postgres::PgPoolOptions, Row};
use std::{
    env,
    fs::{self, OpenOptions},
    io::Write,
    os::unix::fs::OpenOptionsExt,
    path::PathBuf,
};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let reset = env::args().any(|arg| arg == "--reset");
    let username = env::var("DASHBOARD_USERNAME")
        .unwrap_or_else(|_| "admin".to_string())
        .to_ascii_lowercase();
    if username.is_empty()
        || username.len() > 64
        || !username
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"._-".contains(&c))
    {
        return Err(
            "DASHBOARD_USERNAME must use lowercase letters, digits, dot, dash or underscore".into(),
        );
    }
    let output = PathBuf::from(
        env::var("DASHBOARD_CREDENTIALS_FILE")
            .unwrap_or_else(|_| ".data/dashboard-login.txt".to_string()),
    );
    if output.exists() && !reset {
        return Err(format!(
            "credentials already exist: {} (use --reset to rotate)",
            output.display()
        )
        .into());
    }
    let mut raw = [0u8; 24];
    OsRng.fill_bytes(&mut raw);
    let password = hex::encode(raw);
    let encoded = hash_password(&password)?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    let pending = output.with_extension("pending");
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&pending)?;
    writeln!(file, "username={username}\npassword={password}")?;
    file.sync_all()?;

    let pool = PgPoolOptions::new()
        .max_connections(2)
        .connect(&env::var("DATABASE_URL")?)
        .await?;
    let mut tx = pool.begin().await?;
    let existing = sqlx::query("SELECT id FROM dashboard_users WHERE username=$1 FOR UPDATE")
        .bind(&username)
        .fetch_optional(&mut *tx)
        .await?;
    if existing.is_some() && !reset {
        fs::remove_file(&pending)?;
        return Err("dashboard user already exists (use --reset to rotate)".into());
    }
    let id: uuid::Uuid = if let Some(row) = existing {
        let id = row.try_get("id")?;
        sqlx::query("UPDATE dashboard_users SET password_hash=$2,disabled_at=NULL WHERE id=$1")
            .bind(id)
            .bind(&encoded)
            .execute(&mut *tx)
            .await?;
        id
    } else {
        sqlx::query(
            "INSERT INTO dashboard_users(username,password_hash) VALUES($1,$2) RETURNING id",
        )
        .bind(&username)
        .bind(&encoded)
        .fetch_one(&mut *tx)
        .await?
        .try_get("id")?
    };
    if reset {
        sqlx::query("DELETE FROM dashboard_sessions WHERE user_id=$1")
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    fs::rename(pending, &output)?;
    println!(
        "Dashboard login ready for {username}; credentials saved to {}",
        output.display()
    );
    Ok(())
}

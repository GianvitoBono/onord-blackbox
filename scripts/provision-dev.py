#!/usr/bin/env python3
"""Create one local development vehicle/device after server migrations run."""

import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import uuid

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".data" / "dev-credentials.json"


def main() -> None:
    if OUT.exists():
        raise SystemExit(f"Existing credentials: {OUT} (preserved)")
    vehicle_id = str(uuid.uuid4())
    device_id = str(uuid.uuid4())
    token = secrets.token_urlsafe(36)
    digest = hashlib.sha256(token.encode()).hexdigest()
    sql = f"""
    INSERT INTO vehicles(id, display_name) VALUES ('{vehicle_id}', 'Development car');
    INSERT INTO devices(id, vehicle_id, display_name, token_hash)
      VALUES ('{device_id}', '{vehicle_id}', 'Development Nord 2', decode('{digest}', 'hex'));
    INSERT INTO device_vehicle_assignments(device_id, vehicle_id, assigned_at)
      VALUES ('{device_id}', '{vehicle_id}', now());
    """
    subprocess.run(
        ["docker", "compose", "--env-file", ".env", "exec", "-T", "timescaledb",
         "psql", "-v", "ON_ERROR_STOP=1", "-U", "blackbox", "-d", "blackbox"],
        cwd=ROOT, input=sql, text=True, check=True, stdout=subprocess.DEVNULL,
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"vehicleId": vehicle_id, "deviceId": device_id, "deviceToken": token}, indent=2) + "\n")
    OUT.chmod(0o600)
    print(f"Development device ready. Credentials saved: {OUT}")


if __name__ == "__main__":
    main()

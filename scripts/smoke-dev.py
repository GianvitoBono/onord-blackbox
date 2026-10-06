#!/usr/bin/env python3
"""Check local Rust API ingest replay/conflict and dashboard reads."""

import json
import http.cookiejar
from pathlib import Path
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parent.parent
CREDS = json.loads((ROOT / ".data" / "dev-credentials.json").read_text())
BASE = "http://127.0.0.1:8080"
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(path: str, token: str | None, body: dict | None = None) -> tuple[int, object]:
    raw = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=raw, headers=headers, method="GET" if body is None else "POST")
    try:
        with OPENER.open(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main() -> None:
    dashboard_credentials = dict(line.split("=", 1) for line in (ROOT / ".data" / "dashboard-login.txt").read_text().splitlines())
    login = request("/api/v1/auth/login", None, dashboard_credentials)
    assert login[0] == 200, login
    now = datetime.now(timezone.utc).replace(microsecond=0)
    iso = lambda value: value.isoformat().replace("+00:00", "Z")
    batch = {
        "schemaVersion": 1, "deviceId": CREDS["deviceId"], "batchId": str(uuid.uuid4()),
        "trip": {"id": str(uuid.uuid4()), "startedAt": iso(now), "endedAt": iso(now + timedelta(minutes=1)),
                 "startReason": "smoke_test", "endReason": "smoke_test"},
        "gpsSamples": [{"sampleId": str(uuid.uuid4()), "observedAt": iso(now),
                        "latitude": 45.4642, "longitude": 9.19}],
        "deviceSamples": [], "obdSamples": [],
    }
    path = "/api/v1/telemetry/batches"
    first = request(path, CREDS["deviceToken"], batch)
    replay = request(path, CREDS["deviceToken"], batch)
    altered = {**batch, "gpsSamples": [{**batch["gpsSamples"][0], "longitude": 9.20}]}
    conflict = request(path, CREDS["deviceToken"], altered)
    vehicles = request("/api/v1/vehicles", None)
    trips = request(f"/api/v1/vehicles/{CREDS['vehicleId']}/trips", None)
    gps = request(f"/api/v1/trips/{batch['trip']['id']}/gps", None)
    assert first[0] == 200 and first[1] == replay[1], (first, replay)
    assert conflict[0] == 409, conflict
    assert vehicles[0] == 200 and any(v["id"] == CREDS["vehicleId"] for v in vehicles[1]), vehicles
    assert trips[0] == 200 and any(t["id"] == batch["trip"]["id"] for t in trips[1]), trips
    assert gps[0] == 200 and len(gps[1]) == 1, gps
    print("PASS ingest, idempotent replay, altered-batch rejection, dashboard reads")


if __name__ == "__main__":
    main()

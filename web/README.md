# Opnord dashboard

React + TypeScript + Vite dashboard for the private vehicle telemetry API.

## Local development

Run `npm install` and `npm run dev`. Vite proxies `/api` to `http://127.0.0.1:8080`, so the browser uses the same origin during local development. For deployment, route `/api` and static dashboard files through the same HTTPS origin; `VITE_API_BASE_URL` is available if a separately configured API origin is required. Sign in with username/password from `.data/dashboard-login.txt` in the repository root; API manages an HttpOnly session cookie.

The client reads `GET /api/v1/vehicles`, `GET /api/v1/vehicles/{id}/trips?limit=30`, and `GET /api/v1/trips/{id}/gps?limit=300`. Vehicle and trip fields use the backend's camelCase response shape. Device status is shown as unavailable because the current read contract does not expose a device assignment ID or status response.

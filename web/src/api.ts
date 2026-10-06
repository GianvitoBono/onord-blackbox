export type Vehicle = {
  id: string
  displayName: string
  make?: string | null
  model?: string | null
  modelYear?: number | null
  currentDeviceId?: string | null
}

export type VehicleInput = { displayName: string; make?: string | null; model?: string | null; modelYear?: number | null }

export type Trip = {
  id: string
  startedAt: string
  endedAt?: string | null
  startReason?: string | null
  endReason?: string | null
  distanceGpsM?: number | null
  distanceObdM?: number | null
}

export type DeviceStatus = {
  id?: string
  display_name?: string
  online?: boolean
  lastSeenAt?: string | null
  lastReceivedAt?: string | null
  powerConnected?: boolean | null
  batteryPct?: number | null
  batteryTempC?: number | null
}

export type ManagedDevice = { id: string; vehicleId: string; displayName: string; tokenRevokedAt?: string | null; lastReceivedAt?: string | null }
export type PendingDevice = { deviceId: string; reason: string; firstSeenAt: string; lastSeenAt: string; attemptCount: number; lastBatchId: string; vehicleId?: string; displayName?: string; approvable: boolean }
export type RotatedDeviceToken = { deviceId: string; deviceToken: string; credentialsFileUpdated?: boolean }

export type GpsSample = {
  sampleId: string
  observedAt: string
  deltaSec?: number | null
  deltaM?: number | null
  latitude: number
  longitude: number
  altitudeM?: number | null
  speedMps?: number | null
  bearingDeg?: number | null
  horizontalAccuracyM?: number | null
}

export type TripEvent = {
  eventId: string
  stopId: string
  kind: 'stop_start' | 'stop_end'
  observedAt: string
  latitude: number
  longitude: number
}

export type MetricDefinition = { name: string; unit: string; description?: string | null }

export async function updateMetricDefinition(name: string, displayName: string, unit: string): Promise<MetricDefinition> {
  return request<MetricDefinition>(`/api/v1/metrics/${encodeURIComponent(name)}`, {
    method: 'PATCH',
    body: JSON.stringify({ displayName, unit }),
  })
}
export type MetricSample = { sampleId: string; observedAt: string; name: string; unit: string; value: number }
export type VehicleStatus = { tripId: string | null; gps: GpsSample | null; metrics: MetricSample[] }
export type EcuIdentityRead = { command: string; status: string; raw: string; values_by_ecu?: Record<string, string>; detail?: string | null }
export type EcuIdentityReport = { summary?: string; adapter?: string | null; protocol_number?: string | null; protocol_name?: string | null; error?: string | null; reads?: EcuIdentityRead[] }
export type VehicleEcuIdentity = { deviceId: string; observedAt: string; receivedAt: string; report: EcuIdentityReport }

export class ApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

export type AuthSession = { username: string }

const apiBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')

function unwrapList<T>(value: unknown): T[] {
  if (Array.isArray(value)) return value as T[]
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>
    if (Array.isArray(record.data)) return record.data as T[]
    if (Array.isArray(record.items)) return record.items as T[]
    if (Array.isArray(record.vehicles)) return record.vehicles as T[]
    if (Array.isArray(record.trips)) return record.trips as T[]
  }
  return []
}

function normalizeVehicle(value: Vehicle & Record<string, unknown>): Vehicle {
  return {
    ...value,
    displayName: value.displayName ?? (value.display_name as string | undefined) ?? 'Unnamed vehicle',
    modelYear: value.modelYear ?? (value.model_year as number | undefined),
    currentDeviceId: value.currentDeviceId ?? (value.current_device_id as string | undefined),
  }
}

function normalizeTrip(value: Trip & Record<string, unknown>): Trip {
  return {
    ...value,
    startedAt: value.startedAt ?? (value.started_at as string | undefined) ?? '',
    endedAt: value.endedAt ?? (value.ended_at as string | undefined),
    startReason: value.startReason ?? (value.start_reason as string | undefined),
    endReason: value.endReason ?? (value.end_reason as string | undefined),
    distanceGpsM: value.distanceGpsM ?? (value.distance_gps_m as number | undefined),
    distanceObdM: value.distanceObdM ?? (value.distance_obd_m as number | undefined),
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${apiBase}${path}`, {
      ...init,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...init.headers },
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError('API non raggiungibile. Controlla indirizzo e connessione.')
  }
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      throw new ApiError('Sessione scaduta. Accedi di nuovo.', response.status)
    }
    if (response.status === 404) throw new ApiError('Risorsa non trovata.', 404)
    throw new ApiError(`Errore API (${response.status}).`, response.status)
  }
  if (response.status === 204) return [] as T
  try {
    return (await response.json()) as T
  } catch {
    throw new ApiError('Risposta API non valida.', response.status)
  }
}

export async function login(username: string, password: string): Promise<AuthSession> {
  return request<AuthSession>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
}

export async function fetchSession(): Promise<AuthSession> {
  return request<AuthSession>('/api/v1/auth/session')
}

export async function logout(): Promise<void> {
  await request<void>('/api/v1/auth/logout', { method: 'POST' })
}

export async function fetchVehicles(signal?: AbortSignal) {
  return unwrapList<Vehicle & Record<string, unknown>>(await request<unknown>('/api/v1/vehicles', { signal })).map(normalizeVehicle)
}

export async function fetchVehicleStatus(vehicleId: string, signal?: AbortSignal): Promise<VehicleStatus> {
  return request<VehicleStatus>(`/api/v1/vehicles/${encodeURIComponent(vehicleId)}/status`, { signal })
}

export async function fetchVehicleEcuIdentity(vehicleId: string, signal?: AbortSignal): Promise<VehicleEcuIdentity | null> {
  try {
    const result = await request<VehicleEcuIdentity | VehicleEcuIdentity[]>(`/api/v1/vehicles/${encodeURIComponent(vehicleId)}/ecu-identity`, { signal })
    return Array.isArray(result) ? null : result
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

export async function createVehicle(input: VehicleInput): Promise<Vehicle> {
  return normalizeVehicle(await request<Vehicle & Record<string, unknown>>('/api/v1/vehicles', { method: 'POST', body: JSON.stringify(input) }))
}

export async function updateVehicle(vehicleId: string, input: VehicleInput): Promise<Vehicle> {
  return normalizeVehicle(await request<Vehicle & Record<string, unknown>>(`/api/v1/vehicles/${encodeURIComponent(vehicleId)}`, { method: 'PUT', body: JSON.stringify(input) }))
}

export async function fetchDevices(signal?: AbortSignal): Promise<ManagedDevice[]> {
  return unwrapList<ManagedDevice>(await request<unknown>('/api/v1/devices', { signal }))
}

export async function fetchPendingDevices(signal?: AbortSignal): Promise<PendingDevice[]> {
  return unwrapList<PendingDevice>(await request<unknown>('/api/v1/devices/pending', { signal }))
}

export async function approvePendingDevice(deviceId: string, password: string, vehicleId: string, displayName: string) {
  return request<{ deviceId: string; vehicleId: string; displayName: string }>(`/api/v1/devices/pending/${encodeURIComponent(deviceId)}/approve`, { method: 'POST', body: JSON.stringify({ password, vehicleId, displayName }) })
}

export async function rotateDeviceToken(deviceId: string, password: string, deviceToken?: string): Promise<RotatedDeviceToken> {
  return request<RotatedDeviceToken>(`/api/v1/devices/${encodeURIComponent(deviceId)}/rotate-token`, { method: 'POST', body: JSON.stringify({ password, ...(deviceToken ? { deviceToken } : {}) }) })
}

export async function fetchTrips(vehicleId: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ limit: '30' })
  return unwrapList<Trip & Record<string, unknown>>(await request<unknown>(`/api/v1/vehicles/${encodeURIComponent(vehicleId)}/trips?${params}`, { signal })).map(normalizeTrip)
}

export async function fetchDeviceStatus(deviceId: string, signal?: AbortSignal) {
  return request<DeviceStatus>(`/api/v1/devices/${encodeURIComponent(deviceId)}/status`, { signal })
}

export async function fetchTripGps(tripId: string, signal?: AbortSignal) {
  const points: GpsSample[] = []
  const pageSize = 5000
  while (true) {
    const params = new URLSearchParams({ limit: String(pageSize) })
    const last = points[points.length - 1]
    if (last) {
      params.set('afterAt', last.observedAt)
      params.set('afterSampleId', last.sampleId)
    }
    const page = unwrapList<GpsSample>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/gps?${params}`, { signal }))
    if (last && page[page.length - 1]?.sampleId === last.sampleId) return points
    points.push(...page)
    if (page.length < pageSize) return points
  }
}

export async function fetchTripEvents(tripId: string, signal?: AbortSignal): Promise<TripEvent[]> {
  try {
    return unwrapList<TripEvent>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/events`, { signal }))
      .filter((event) => (event.kind === 'stop_start' || event.kind === 'stop_end') && Number.isFinite(event.latitude) && Number.isFinite(event.longitude))
  } catch (error) {
    // Older servers do not expose trip events; keep their routes usable.
    if (error instanceof ApiError && error.status === 404) return []
    throw error
  }
}

export async function fetchTripTelemetryAt(tripId: string, at: string, toleranceMs = 2000, signal?: AbortSignal): Promise<MetricSample[]> {
  const params = new URLSearchParams({ at, toleranceMs: String(toleranceMs) })
  return unwrapList<MetricSample>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/telemetry-at?${params}`, { signal }))
}

export async function fetchTripMetricCatalog(tripId: string, signal?: AbortSignal): Promise<MetricDefinition[]> {
  return unwrapList<MetricDefinition>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/metric-catalog`, { signal }))
    .filter((metric) => typeof metric.name === 'string' && typeof metric.unit === 'string')
}

export async function fetchTripMetricSamples(tripId: string, name: string, from: string, to: string, signal?: AbortSignal): Promise<MetricSample[]> {
  const params = new URLSearchParams({ name, from, to, limit: '2000' })
  return unwrapList<MetricSample>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/metrics?${params}`, { signal }))
}

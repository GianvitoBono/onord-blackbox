export type Vehicle = {
  id: string
  displayName: string
  make?: string | null
  model?: string | null
  modelYear?: number | null
  currentDeviceId?: string | null
}

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

export type MetricDefinition = { name: string; unit: string; description?: string | null }
export type MetricSample = { sampleId: string; observedAt: string; name: string; unit: string; value: number }

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
    throw new ApiError('Could not reach the API. Check the server address and connection.')
  }
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      throw new ApiError('Your session has expired. Sign in again.', response.status)
    }
    if (response.status === 404) throw new ApiError('This read endpoint is not available yet.', 404)
    throw new ApiError(`The API returned an error (${response.status}).`, response.status)
  }
  if (response.status === 204) return [] as T
  try {
    return (await response.json()) as T
  } catch {
    throw new ApiError('The API response was not valid JSON.', response.status)
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

export async function fetchTrips(vehicleId: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ limit: '30' })
  return unwrapList<Trip & Record<string, unknown>>(await request<unknown>(`/api/v1/vehicles/${encodeURIComponent(vehicleId)}/trips?${params}`, { signal })).map(normalizeTrip)
}

export async function fetchDeviceStatus(deviceId: string, signal?: AbortSignal) {
  return request<DeviceStatus>(`/api/v1/devices/${encodeURIComponent(deviceId)}/status`, { signal })
}

export async function fetchTripGps(tripId: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ limit: '300' })
  return unwrapList<GpsSample>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/gps?${params}`, { signal }))
}

export async function fetchTripMetricCatalog(tripId: string, signal?: AbortSignal): Promise<MetricDefinition[]> {
  return unwrapList<MetricDefinition>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/metric-catalog`, { signal }))
    .filter((metric) => typeof metric.name === 'string' && typeof metric.unit === 'string')
}

export async function fetchTripMetricSamples(tripId: string, name: string, from: string, to: string, signal?: AbortSignal): Promise<MetricSample[]> {
  const params = new URLSearchParams({ name, from, to, limit: '2000' })
  return unwrapList<MetricSample>(await request<unknown>(`/api/v1/trips/${encodeURIComponent(tripId)}/metrics?${params}`, { signal }))
}

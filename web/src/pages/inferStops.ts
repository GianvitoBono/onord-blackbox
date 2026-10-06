import type { GpsSample, TripEvent } from '../api'

const MIN_STOP_MS = 10_000
const MAX_FIX_GAP_MS = 60_000
const MAX_ACCURACY_M = 20
const STATIONARY_RADIUS_M = 25
const DEPARTURE_DISTANCE_M = 65

function meters(a: GpsSample, b: GpsSample) {
  const lat = (a.latitude + b.latitude) * Math.PI / 360
  const north = (a.latitude - b.latitude) * 111_195
  const east = (a.longitude - b.longitude) * 111_195 * Math.cos(lat)
  return Math.hypot(north, east)
}

/** Fallback for older trips with no persisted stop events. Require a stationary cluster and a real departure. */
export function inferStops(samples: GpsSample[]): TripEvent[] {
  const points = samples.filter(point => Number.isFinite(point.latitude) && Number.isFinite(point.longitude)
    && (point.horizontalAccuracyM == null || point.horizontalAccuracyM <= MAX_ACCURACY_M))
  const result: TripEvent[] = []
  let anchor: GpsSample | null = null
  let lastStill: GpsSample | null = null
  let stillCount = 0
  for (const point of points) {
    const at = Date.parse(point.observedAt)
    if (!Number.isFinite(at)) continue
    if (!anchor || !lastStill || at - Date.parse(lastStill.observedAt) > MAX_FIX_GAP_MS) {
      anchor = point
      lastStill = point
      stillCount = 1
      continue
    }
    const distance = meters(anchor, point)
    const speed = point.speedMps
    if (distance <= STATIONARY_RADIUS_M && (speed == null || speed <= 0.8)) {
      lastStill = point
      stillCount++
      continue
    }
    const duration = Date.parse(lastStill.observedAt) - Date.parse(anchor.observedAt)
    if (duration >= MIN_STOP_MS && stillCount >= 3 && distance < DEPARTURE_DISTANCE_M
      && at - Date.parse(lastStill.observedAt) <= MAX_FIX_GAP_MS) continue
    if (duration >= MIN_STOP_MS && stillCount >= 3 && distance >= DEPARTURE_DISTANCE_M
      && (speed == null || speed >= 1.5)) {
      const stopId = `gps-${anchor.sampleId}`
      result.push({ eventId: `${stopId}-start`, stopId, kind: 'stop_start',
        observedAt: anchor.observedAt, latitude: anchor.latitude, longitude: anchor.longitude, inferred: true })
      result.push({ eventId: `${stopId}-end`, stopId, kind: 'stop_end',
        observedAt: point.observedAt, latitude: point.latitude, longitude: point.longitude, inferred: true })
    }
    anchor = point
    lastStill = point
    stillCount = 1
  }
  return result
}

import type { GpsSample, MetricDefinition } from '../api'

const dateTime = new Intl.DateTimeFormat('it-IT', { dateStyle: 'medium', timeStyle: 'short' })
export const time = new Intl.DateTimeFormat('it-IT', { timeStyle: 'short' })
export const formatDate = (value?: string | null) => value && !Number.isNaN(Date.parse(value)) ? dateTime.format(new Date(value)) : '—'
export const formatDistance = (meters?: number | null) => meters == null ? '—' : meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${Math.round(meters)} m`
export const message = (error: unknown) => error instanceof Error ? error.message : 'Errore di caricamento.'
export const pages = ['overview', 'journeys', 'explore', 'fleet', 'admin', 'settings'] as const
export type Page = typeof pages[number]
export const labels: Record<Page, string> = { overview: 'Panoramica', journeys: 'Viaggi', explore: 'Esplora', fleet: 'Flotta', admin: 'Admin', settings: 'Impostazioni' }
export const glyphs: Record<Page, string> = { overview: '◫', journeys: '↝', explore: '◎', fleet: '▤', admin: '◇', settings: '⚙' }
export function pageFromHash(): Page { const value = location.hash.slice(1); return pages.includes(value as Page) ? value as Page : 'overview' }
export function metricLabel(metric: MetricDefinition) { return metric.description || metric.name.replace(/^obd\.pid\./, 'PID ').replaceAll('.', ' · ').replaceAll('_', ' ') }
export function nearestGps(samples: GpsSample[], at: string) {
  if (!samples.length) return null
  const target = Date.parse(at)
  if (!Number.isFinite(target)) return null
  let low = 0
  let high = samples.length - 1
  while (low < high) {
    const middle = (low + high) >>> 1
    if (Date.parse(samples[middle].observedAt) < target) low = middle + 1
    else high = middle
  }
  if (low > 0 && Math.abs(Date.parse(samples[low - 1].observedAt) - target) < Math.abs(Date.parse(samples[low].observedAt) - target)) return samples[low - 1]
  return samples[low]
}
export type NearbyReading = { name: string; unit: string; observedAt: string; value: number }

import { useEffect, useMemo, useState, type Dispatch, type SetStateAction } from 'react'
import { fetchTripMetricSamples, type MetricDefinition, type MetricSample, type Trip, type Vehicle } from '../api'
import TelemetryChart from '../components/TelemetryChart'
import { formatDate, formatDistance, message, metricLabel } from './format'

type Props = {
  vehicle?: Vehicle
  trips: Trip[]
  tripId: string
  setTripId: Dispatch<SetStateAction<string>>
  selectedTrip?: Trip
  catalog: MetricDefinition[]
  detailLoading: boolean
  detailError: string
  onRefresh: () => void
  now: number
}

type WindowChoice = '15m' | '1h' | 'all'
const sampleLimit = 5000
const preferenceKey = 'opnord.dashboard.metrics'
const priority = ['speed', 'rpm', 'engine_load', 'map', 'boost', 'turbo', 'actual_torque', 'rail_pressure', 'maf', 'coolant']

function savedMetrics(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(preferenceKey) || '[]')
    return Array.isArray(value) ? value.filter((name): name is string => typeof name === 'string').slice(0, 12) : []
  } catch { return [] }
}

function defaultMetrics(catalog: MetricDefinition[]) {
  return [...catalog].sort((a, b) => {
    const rank = (name: string) => {
      const index = priority.findIndex(part => name.endsWith(part) || name.includes(`.${part}`))
      return index === -1 ? priority.length : index
    }
    return rank(a.name) - rank(b.name) || a.name.localeCompare(b.name)
  }).slice(0, 6).map(metric => metric.name)
}

function MetricPanel({ tripId, metric, from, to, onHover, cursor, onZoom }: {
  tripId: string
  metric: MetricDefinition
  from: string
  to: string
  onHover: (at: string | null) => void
  cursor: string | null
  onZoom: (from: number, to: number) => void
}) {
  const [samples, setSamples] = useState<MetricSample[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setSamples([])
    void fetchTripMetricSamples(tripId, metric.name, from, to, controller.signal, sampleLimit)
      .then(rows => { if (!controller.signal.aborted) setSamples(rows) })
      .catch(failure => { if (!controller.signal.aborted) setError(message(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [tripId, metric.name, from, to])
  const values = samples.map(sample => sample.value).filter(Number.isFinite)
  const latest = samples[samples.length - 1]
  const format = (value?: number) => value == null ? '—' : value.toLocaleString('it-IT', { maximumFractionDigits: 2 })
  return <article className="metric-panel">
    <header className="metric-panel-head">
      <div><h2>{metricLabel(metric)}</h2><code>{metric.name}</code></div>
      <strong>{format(latest?.value)} <small>{metric.unit}</small></strong>
    </header>
    <div className="metric-panel-stats">
      <span>Min <b>{format(values.length ? Math.min(...values) : undefined)}</b></span>
      <span>Max <b>{format(values.length ? Math.max(...values) : undefined)}</b></span>
      <span>Campioni <b>{samples.length.toLocaleString('it-IT')}</b></span>
    </div>
    {error ? <p className="metric-panel-state" role="alert">{error}</p> : loading ? <p className="metric-panel-state">Caricamento segnale…</p> : <TelemetryChart samples={samples} unit={metric.unit} selectedAt={cursor} onHoverAt={onHover} onZoom={onZoom} />}
    {samples.length >= sampleLimit && <p className="metric-panel-limit">Limite di {sampleLimit.toLocaleString('it-IT')} campioni raggiunto: restringi intervallo per vedere dati successivi.</p>}
  </article>
}

export default function MetricsDashboard({ vehicle, trips, tripId, setTripId, selectedTrip, catalog, detailLoading, detailError, onRefresh, now }: Props) {
  const [selected, setSelected] = useState<string[]>(savedMetrics)
  const [addName, setAddName] = useState('')
  const [windowChoice, setWindowChoice] = useState<WindowChoice>('15m')
  const [custom, setCustom] = useState<[number, number] | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  useEffect(() => { setCustom(null); setCursor(null) }, [tripId])
  useEffect(() => {
    if (!catalog.length) return
    setSelected(current => {
      const valid = current.filter(name => catalog.some(metric => metric.name === name))
      return valid.length ? valid : defaultMetrics(catalog)
    })
  }, [catalog])
  useEffect(() => { if (catalog.length) localStorage.setItem(preferenceKey, JSON.stringify(selected)) }, [catalog, selected])
  const shown = selected.map(name => catalog.find(metric => metric.name === name)).filter((metric): metric is MetricDefinition => Boolean(metric))
  const available = catalog.filter(metric => !shown.some(item => item.name === metric.name))
  const range = useMemo(() => {
    if (!selectedTrip) return null
    const start = Date.parse(selectedTrip.startedAt)
    const end = selectedTrip.endedAt ? Date.parse(selectedTrip.endedAt) : now
    if (!Number.isFinite(start) || !Number.isFinite(end)) return null
    const validEnd = Math.max(start + 1000, end)
    const duration = windowChoice === '15m' ? 15 * 60_000 : windowChoice === '1h' ? 60 * 60_000 : Infinity
    const from = custom ? Math.max(start, custom[0]) : Math.max(start, validEnd - duration)
    const to = custom ? Math.min(validEnd, custom[1]) : validEnd
    return { from: new Date(from).toISOString(), to: new Date(Math.max(from + 1000, to)).toISOString() }
  }, [selectedTrip, now, windowChoice, custom])
  function removeMetric(name: string) { setSelected(current => current.filter(item => item !== name)) }
  function addMetric() {
    if (!addName || shown.some(metric => metric.name === addName)) return
    setSelected(current => [...current, addName].slice(0, 12))
    setAddName('')
  }
  return <section className="metrics-dashboard">
    <div className="page-heading metrics-heading">
      <div><span className="eyebrow">TELEMETRIA</span><h1>Metriche</h1><p>{vehicle?.displayName || 'Mezzo'} · segnali del viaggio su asse temporale condiviso</p></div>
      <button className="outline-button" type="button" onClick={onRefresh} disabled={detailLoading}>{detailLoading ? 'Aggiornamento…' : '↻ Aggiorna'}</button>
    </div>
    <div className="metrics-toolbar">
      <label>Viaggio<select className="trip-selector" value={tripId} onChange={event => setTripId(event.target.value)} aria-label="Seleziona viaggio per metriche">
        {!trips.length && <option value="">Nessun viaggio</option>}{trips.map(trip => <option key={trip.id} value={trip.id}>{formatDate(trip.startedAt)} · {formatDistance(trip.distanceGpsM ?? trip.distanceObdM)}</option>)}
      </select></label>
      <div className="metrics-time-choices" role="group" aria-label="Intervallo temporale">
        {([['15m', '15 min'], ['1h', '1 ora'], ['all', 'Tutto']] as const).map(([value, label]) => <button key={value} type="button" className={windowChoice === value && !custom ? 'selected' : ''} onClick={() => { setWindowChoice(value); setCustom(null) }}>{label}</button>)}
        {custom && <button type="button" className="selected" onClick={() => setCustom(null)}>Zoom ×</button>}
      </div>
      <label>Aggiungi segnale<select value={addName} onChange={event => setAddName(event.target.value)} aria-label="Segnale da aggiungere"><option value="">Scegli metrica</option>{available.map(metric => <option key={metric.name} value={metric.name}>{metricLabel(metric)} ({metric.unit || '—'})</option>)}</select></label>
      <button className="outline-button" type="button" onClick={addMetric} disabled={!addName || shown.length >= 12}>Aggiungi</button>
    </div>
    {range && <div className="metrics-range">{formatDate(range.from)} — {formatDate(range.to)} <span>Trascina su grafico per zoom sincronizzato</span></div>}
    {detailError && <div className="banner error" role="alert">{detailError}</div>}
    {!selectedTrip ? <div className="empty-message">Seleziona viaggio per vedere metriche.</div> : !catalog.length ? <div className="empty-message">{detailLoading ? 'Caricamento metriche…' : 'Nessun segnale registrato per questo viaggio.'}</div> : <>
      <div className="metrics-picks">{shown.map(metric => <button key={metric.name} type="button" onClick={() => removeMetric(metric.name)} aria-label={`Rimuovi ${metricLabel(metric)}`}>{metricLabel(metric)} <span>×</span></button>)}</div>
      {shown.length && range ? <div className="metrics-grid">{shown.map(metric => <MetricPanel key={`${metric.name}:${range.from}:${range.to}`} tripId={tripId} metric={metric} from={range.from} to={range.to} cursor={cursor} onHover={setCursor} onZoom={(from, to) => { setCustom([from, to]); setCursor(null) }} />)}</div> : <div className="empty-message">Aggiungi segnale per creare pannello.</div>}
    </>}
  </section>
}

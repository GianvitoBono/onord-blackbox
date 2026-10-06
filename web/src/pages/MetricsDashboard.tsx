import { useEffect, useMemo, useState, type Dispatch, type SetStateAction } from 'react'
import { fetchTripMetricSamples, fetchTripRawObd, type MetricDefinition, type MetricSample, type RawObdReply, type Trip, type Vehicle } from '../api'
import TelemetryChart from '../components/TelemetryChart'
import { formatDate, formatDistance, message } from './format'
import { groupOrder, guideFor, isEncoded, metricSource } from './metricGuide'
import { decodeHistoricalSamples, historicalSource, isHistoricalMetric, withHistoricalMetrics } from './historicalMetrics'
import { rawPidValue } from './pidCatalog'

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
type Preset = 'Panoramica' | 'Motore' | 'Aria e turbo' | 'Emissioni' | 'Personalizzata'
const sampleLimit = 5000
const preferenceKey = 'opnord.dashboard.metrics'
const presetNames: Record<Exclude<Preset, 'Personalizzata'>, string[]> = {
  Panoramica: ['obd.pid.010d', 'obd.pid.010c', 'obd.pid.0105', 'obd.pid.010b', 'obd.calc.engine_torque_nm'],
  Motore: ['obd.pid.010c', 'obd.pid.0104', 'obd.calc.engine_torque_nm', 'obd.pid.0105', 'obd.pid.0162'],
  'Aria e turbo': ['obd.pid.010b', 'obd.pid.0133', 'obd.calc.manifold_gauge_pressure_kpa', 'obd.pid.0110'],
  Emissioni: ['obd.pid.0134.equivalence_ratio', 'obd.pid.0134.current_ma', 'obd.pid.018e.friction_torque_pct'],
}

function savedMetrics(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(preferenceKey) || '[]')
    return Array.isArray(value) ? value.filter((name): name is string => typeof name === 'string').slice(0, 8) : []
  } catch { return [] }
}

function presetMetrics(catalog: MetricDefinition[], preset: Exclude<Preset, 'Personalizzata'>): string[] {
  const usable = catalog.filter(metric => !isEncoded(metric))
  const present = presetNames[preset].filter(name => usable.some(metric => metric.name === name))
  if (present.length) return present
  return usable.filter(metric => preset === 'Panoramica' || guideFor(metric).group === preset).slice(0, 5).map(metric => metric.name)
}

function MetricPanel({ tripId, metric, from, to, onHover, cursor, onZoom, onRemove, featured, historical }: {
  tripId: string
  metric: MetricDefinition
  from: string
  to: string
  onHover: (at: string | null) => void
  cursor: string | null
  onZoom: (from: number, to: number) => void
  onRemove: () => void
  featured: boolean
  historical: boolean
}) {
  const [samples, setSamples] = useState<MetricSample[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const guide = guideFor(metric)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setSamples([])
    void fetchTripMetricSamples(tripId, historical ? historicalSource(metric.name) : metric.name, from, to, controller.signal, sampleLimit)
      .then(rows => { if (!controller.signal.aborted) setSamples(historical ? decodeHistoricalSamples(metric.name, rows) : rows) })
      .catch(failure => { if (!controller.signal.aborted) setError(message(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [tripId, metric.name, from, to, historical])
  const values = samples.map(sample => sample.value).filter(Number.isFinite)
  const latest = samples[samples.length - 1]
  const format = (value?: number) => value == null ? '—' : value.toLocaleString('it-IT', { maximumFractionDigits: metric.unit === 'ratio' ? 3 : 2 })
  return <article className={`metric-panel ${featured ? 'metric-panel--featured' : ''}`}>
    <header className="metric-panel-head">
      <div className="metric-panel-copy"><span className="metric-panel-group">{guide.group} · {metricSource(metric.name)}</span><h2>{guide.title}</h2><p>{guide.explanation}</p></div>
      <div className="metric-panel-current"><strong>{format(latest?.value)}</strong><span>{metric.unit || 'senza unità'}</span></div>
      <button className="metric-panel-remove" type="button" aria-label={`Rimuovi ${guide.title}`} title="Rimuovi grafico" onClick={onRemove}>×</button>
    </header>
    {(guide.note || historical) && <p className="metric-panel-note">{guide.note}{historical && <> {guide.note ? '· ' : ''}Viaggi precedenti: calcolo dai byte OBD salvati come intero grezzo.</>}</p>}
    <div className="metric-panel-stats">
      <span>Min <b>{format(values.length ? Math.min(...values) : undefined)}</b></span>
      <span>Max <b>{format(values.length ? Math.max(...values) : undefined)}</b></span>
      <span>Campioni <b>{samples.length.toLocaleString('it-IT')}</b></span>
      <details><summary>Origine dato</summary><code>{metric.name}</code></details>
    </div>
    {error ? <p className="metric-panel-state" role="alert">{error}</p> : loading ? <p className="metric-panel-state">Caricamento segnale…</p> : <TelemetryChart samples={samples} unit={metric.unit} selectedAt={cursor} onHoverAt={onHover} onZoom={onZoom} />}
    {samples.length >= sampleLimit && <p className="metric-panel-limit">Limite di {sampleLimit.toLocaleString('it-IT')} campioni raggiunto: restringi intervallo per vedere dati successivi.</p>}
  </article>
}

export default function MetricsDashboard({ vehicle, trips, tripId, setTripId, selectedTrip, catalog, detailLoading, detailError, onRefresh, now }: Props) {
  const viewCatalog = useMemo(() => withHistoricalMetrics(catalog), [catalog])
  const [selected, setSelected] = useState<string[]>(savedMetrics)
  const [preset, setPreset] = useState<Preset>(savedMetrics().length ? 'Personalizzata' : 'Panoramica')
  const [catalogOpen, setCatalogOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [exportStatus, setExportStatus] = useState('')
  const [rawExportStatus, setRawExportStatus] = useState('')
  const [windowChoice, setWindowChoice] = useState<WindowChoice>('15m')
  const [custom, setCustom] = useState<[number, number] | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  useEffect(() => { setCustom(null); setCursor(null) }, [tripId])
  useEffect(() => {
    if (!viewCatalog.length) return
    setSelected(current => {
      if (preset !== 'Personalizzata') return presetMetrics(viewCatalog, preset)
      const valid = current.filter(name => viewCatalog.some(metric => metric.name === name && !isEncoded(metric)))
      return valid.length ? valid : presetMetrics(viewCatalog, 'Panoramica')
    })
  }, [viewCatalog, preset])
  useEffect(() => { if (catalog.length) localStorage.setItem(preferenceKey, JSON.stringify(selected)) }, [catalog, selected])
  const shown = selected.map(name => viewCatalog.find(metric => metric.name === name && !isEncoded(metric))).filter((metric): metric is MetricDefinition => Boolean(metric))
  const encoded = catalog.filter(isEncoded)
  const available = viewCatalog.filter(metric => !isEncoded(metric) && !shown.some(item => item.name === metric.name))
  const matching = available.filter(metric => {
    const query = search.trim().toLocaleLowerCase('it-IT')
    if (!query) return true
    const guide = guideFor(metric)
    return `${guide.title} ${guide.explanation} ${guide.group} ${metric.name}`.toLocaleLowerCase('it-IT').includes(query)
  })
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
  function selectPreset(next: Exclude<Preset, 'Personalizzata'>) {
    setPreset(next)
    setSelected(presetMetrics(viewCatalog, next))
    setCatalogOpen(false)
  }
  function removeMetric(name: string) { setPreset('Personalizzata'); setSelected(current => current.filter(item => item !== name)) }
  function addMetric(name: string) {
    if (shown.some(metric => metric.name === name) || shown.length >= 8) return
    setPreset('Personalizzata')
    setSelected(current => [...current, name])
  }
  async function copyEncodedSamples() {
    if (!range || !tripId || !encoded.length) return
    setExportStatus('Raccolta campioni in corso…')
    try {
      const series = await Promise.all(encoded.map(async metric => ({
        name: metric.name, unit: metric.unit, title: guideFor(metric).title,
        samples: (await fetchTripMetricSamples(tripId, metric.name, range.from, range.to, undefined, 120))
          .map(sample => ({ observedAt: sample.observedAt, value: sample.value, hex: rawPidValue(sample.value) })),
      })))
      await navigator.clipboard.writeText(JSON.stringify({ tripId, from: range.from, to: range.to,
        note: 'Interi storici: byte originali, lunghezza e ECU sorgente non disponibili. Max 120 campioni per PID.', series }, null, 2))
      setExportStatus(`Copiati ${series.reduce((sum, item) => sum + item.samples.length, 0)} campioni. Incolla qui il JSON.`)
    } catch (error) { setExportStatus(message(error)) }
  }
  async function downloadRawReplies() {
    if (!tripId) return
    setRawExportStatus('Raccolta risposte OBD…')
    try {
      const replies: RawObdReply[] = []
      while (replies.length < 5000) {
        const page = await fetchTripRawObd(tripId, replies[replies.length - 1])
        replies.push(...page)
        if (page.length < 500) break
      }
      if (!replies.length) { setRawExportStatus('Nessuna risposta completa disponibile per questo viaggio. Serve la nuova versione di app e backend.'); return }
      const blob = new Blob([JSON.stringify({ tripId, exportedAt: new Date().toISOString(),
        truncated: replies.length >= 5000, replies }, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `obd-raw-${tripId}.json`
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
      setRawExportStatus(`${replies.length} risposte scaricate${replies.length >= 5000 ? ' (limite raggiunto)' : ''}.`)
    } catch (error) { setRawExportStatus(message(error)) }
  }
  return <section className="metrics-dashboard">
    <div className="page-heading metrics-heading">
      <div><h1>Analisi telemetria</h1><p>{vehicle?.displayName || 'Mezzo'} · dati del viaggio, letti per sistema dell’auto</p></div>
      <button className="outline-button" type="button" onClick={onRefresh} disabled={detailLoading}>{detailLoading ? 'Aggiornamento…' : 'Aggiorna dati'}</button>
    </div>
    <div className="metrics-toolbar">
      <label>Viaggio<select className="trip-selector" value={tripId} onChange={event => setTripId(event.target.value)} aria-label="Seleziona viaggio per metriche">
        {!trips.length && <option value="">Nessun viaggio</option>}{trips.map(trip => <option key={trip.id} value={trip.id}>{formatDate(trip.startedAt)} · {formatDistance(trip.distanceGpsM ?? trip.distanceObdM)}</option>)}
      </select></label>
      <div className="metrics-time-choices" role="group" aria-label="Intervallo temporale">
        {([['15m', '15 min'], ['1h', '1 ora'], ['all', 'Tutto']] as const).map(([value, label]) => <button key={value} type="button" className={windowChoice === value && !custom ? 'selected' : ''} onClick={() => { setWindowChoice(value); setCustom(null) }}>{label}</button>)}
        {custom && <button type="button" className="selected" onClick={() => setCustom(null)}>Togli zoom ×</button>}
      </div>
      <button className="metrics-catalog-trigger" type="button" aria-expanded={catalogOpen} onClick={() => setCatalogOpen(open => !open)}>{catalogOpen ? 'Chiudi catalogo' : 'Aggiungi segnali'} <span>{available.length}</span></button>
    </div>
    {detailError && <div className="banner error" role="alert">{detailError}</div>}
    {!selectedTrip ? <div className="empty-message">Seleziona viaggio per vedere le misure.</div> : !catalog.length ? <div className="empty-message">{detailLoading ? 'Caricamento metriche…' : 'Nessun segnale registrato per questo viaggio.'}</div> : <>
      <div className="metrics-workspace-head"><div><h2>Leggi il viaggio</h2><p>Ogni grafico usa lo stesso intervallo. Passa il cursore sui dati per confrontare gli stessi istanti; trascina per ingrandire.</p></div><span>{range ? `${formatDate(range.from)} – ${formatDate(range.to)}` : ''}</span></div>
      <div className="metrics-presets" role="group" aria-label="Viste dei segnali">
        {(['Panoramica', 'Motore', 'Aria e turbo', 'Emissioni'] as const).map(name => <button key={name} type="button" className={preset === name ? 'selected' : ''} disabled={!presetMetrics(viewCatalog, name).length} onClick={() => selectPreset(name)}>{name}</button>)}
        {preset === 'Personalizzata' && <span>Vista personalizzata · {shown.length} segnali</span>}
      </div>
      {catalogOpen && <section className="metrics-catalog" aria-label="Catalogo segnali">
        <div className="metrics-catalog-head"><div><h3>Scegli cosa osservare</h3><p>Segnali con unità fisica e spiegazione. Massimo 8 grafici per tenere leggibile il confronto.</p></div><button type="button" onClick={() => setCatalogOpen(false)} aria-label="Chiudi catalogo">×</button></div>
        <label className="metrics-search">Cerca per nome, sistema o PID<input value={search} onChange={event => setSearch(event.target.value)} placeholder="Es. pressione, coppia, 010B" /></label>
        <div className="metrics-catalog-groups">{groupOrder.map(group => {
          const items = matching.filter(metric => guideFor(metric).group === group)
          return items.length ? <div className="metrics-catalog-group" key={group}><h4>{group}</h4><div>{items.map(metric => {
            const guide = guideFor(metric)
            return <button className="metrics-catalog-item" type="button" key={metric.name} disabled={shown.length >= 8} onClick={() => addMetric(metric.name)}>
              <span><strong>{guide.title}</strong><small>{guide.explanation}</small></span><em>{metric.unit || '—'}</em><b aria-hidden="true">+</b>
            </button>
          })}</div></div> : null
        })}</div>
        {!matching.length && <p className="metrics-catalog-empty">Nessun altro segnale misurabile corrisponde alla ricerca.</p>}
        <details className="metrics-encoded"><summary>Risposte OBD originali · {encoded.length} PID codificati</summary>
          <p>La nuova versione conserva i byte di ogni risposta, anche quando il valore è già decodificato.</p>
          {encoded.length > 0 && <><p>Un intero grezzo può contenere più campi o flag. I campi con formula verificata compaiono sopra come segnali separati.</p>
            <ul>{encoded.map(metric => <li key={metric.name}><span>{guideFor(metric).title}</span><code>{metric.name}</code></li>)}</ul></>}
          <div className="metrics-encoded-actions">
            {encoded.length > 0 && <button type="button" onClick={() => void copyEncodedSamples()} disabled={!range || exportStatus.startsWith('Raccolta')}>Copia campioni storici</button>}
            <button type="button" onClick={() => void downloadRawReplies()} disabled={rawExportStatus.startsWith('Raccolta')}>Scarica ultime 5.000 risposte OBD</button>
            <a href="#fleet">Vedi valori grezzi nella Flotta</a>
          </div>
          {exportStatus && <p role="status">{exportStatus}</p>}{rawExportStatus && <p role="status">{rawExportStatus}</p>}
          <p>Il download contiene byte, testo ELM, ECU e stato di parsing raccolti dalla nuova versione dell’app. I viaggi precedenti conservano solo gli interi.</p>
        </details>
      </section>}
      <div className="metrics-active-bar"><strong>{shown.length} {shown.length === 1 ? 'grafico attivo' : 'grafici attivi'}</strong><span>Significato, unità e origine accanto a ogni traccia</span></div>
      {shown.length && range ? <div className="metrics-grid">{shown.map((metric, index) => <MetricPanel key={`${metric.name}:${range.from}:${range.to}`} tripId={tripId} metric={metric} from={range.from} to={range.to} cursor={cursor} onHover={setCursor} onZoom={(from, to) => { setCustom([from, to]); setCursor(null) }} onRemove={() => removeMetric(metric.name)} featured={index === 0} historical={isHistoricalMetric(metric.name) && !catalog.some(item => item.name === metric.name)} />)}</div> : <div className="empty-message">Scegli una vista o aggiungi un segnale dal catalogo.</div>}
    </>}
  </section>
}

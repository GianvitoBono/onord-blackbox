import { useEffect, useState, type Dispatch, type FormEvent, type SetStateAction } from 'react'
import { updateMetricDefinition, type GpsSample, type MetricDefinition, type MetricSample, type TripEvent } from '../api'
import TripMap from '../components/TripMap'
import TelemetryChart from '../components/TelemetryChart'
import { formatDate, formatDistance, metricLabel, nearestGps, type NearbyReading } from './format'

type Props = { tripId: string; detailError: string; onRefresh: () => void; gps: GpsSample[]; events: TripEvent[]; displayPoint: GpsSample | null; setHoverPoint: Dispatch<SetStateAction<GpsSample | null>>; setSelectedPoint: Dispatch<SetStateAction<GpsSample | null>>; displayStopEvent: TripEvent | null; setHoverStopEvent: Dispatch<SetStateAction<TripEvent | null>>; setSelectedStopEvent: Dispatch<SetStateAction<TripEvent | null>>; nearby: NearbyReading[]; nearbyLoading: boolean; catalog: MetricDefinition[]; metricName: string; setMetricName: Dispatch<SetStateAction<string>>; metrics: MetricSample[]; selectedMetric?: MetricDefinition }

function stopDuration(events: TripEvent[], event: TripEvent): string {
  const match = events.find((candidate) => candidate.stopId === event.stopId && candidate.kind !== event.kind)
  if (!match) return event.kind === 'stop_start' ? 'Sosta in corso' : 'Inizio sosta non registrato'
  const seconds = Math.max(0, Math.round(Math.abs(Date.parse(match.observedAt) - Date.parse(event.observedAt)) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours ? `${hours} h ${minutes} min` : `${minutes} min`
}

export default function Explore({ tripId, detailError, onRefresh, gps, events, displayPoint, setHoverPoint, setSelectedPoint, displayStopEvent, setHoverStopEvent, setSelectedStopEvent, nearby, nearbyLoading, catalog, metricName, setMetricName, metrics, selectedMetric }: Props) {
  const [inspectorOpen, setInspectorOpen] = useState(false)
  useEffect(() => { setInspectorOpen(false) }, [tripId])
  useEffect(() => {
    if (!inspectorOpen) return
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') setInspectorOpen(false) }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [inspectorOpen])
  const selectPoint = (sample: GpsSample) => { setHoverPoint(null); setHoverStopEvent(null); setSelectedStopEvent(null); setSelectedPoint(sample); setInspectorOpen(true) }
  const selectStop = (event: TripEvent) => { setHoverPoint(null); setHoverStopEvent(null); setSelectedPoint(null); setSelectedStopEvent(event); setInspectorOpen(true) }
  const [editingMetric, setEditingMetric] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [unit, setUnit] = useState('')
  const [metricBusy, setMetricBusy] = useState(false)
  const [metricMessage, setMetricMessage] = useState('')
  function openMetricEditor() {
    if (!selectedMetric) return
    setDisplayName(selectedMetric.description || selectedMetric.name)
    setUnit(selectedMetric.unit)
    setMetricMessage('')
    setEditingMetric(true)
  }
  async function saveMetric(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedMetric) return
    setMetricBusy(true)
    setMetricMessage('')
    try {
      await updateMetricDefinition(selectedMetric.name, displayName.trim(), unit.trim())
      setEditingMetric(false)
      setMetricMessage('Nome e unità salvati per tutti i viaggi.')
      onRefresh()
    } catch (error) {
      setMetricMessage(error instanceof Error ? error.message : 'Salvataggio non riuscito.')
    } finally { setMetricBusy(false) }
  }
  return (
    <>
{detailError && <div className="banner error">
{detailError}
</div>}
<div className="explore-layout">
<div className="map-column">
<div className="large-map">
<TripMap samples={gps} events={events} selectedSampleId={displayPoint?.sampleId} onPointHover={setHoverPoint} onPointSelect={selectPoint} onEventHover={setHoverStopEvent} onEventSelect={selectStop} />
</div>
</div>
{inspectorOpen && <div className="explore-modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) setInspectorOpen(false) }}>
<div className="explore-modal" role="dialog" aria-modal="true" aria-label="Misure del percorso">
<div className="explore-modal-top"><strong>Misure del percorso</strong><button type="button" className="outline-button" onClick={() => setInspectorOpen(false)} aria-label="Chiudi misure">Chiudi ×</button></div>
<aside className="point-inspector">
<span className="eyebrow">PUNTO SELEZIONATO</span>
{displayStopEvent && <section className="stop-inspector" aria-label="Evento sosta selezionato">
<span className={`stop-event-kind ${displayStopEvent.kind === 'stop_start' ? 'is-start' : 'is-end'}`}>{displayStopEvent.kind === 'stop_start' ? 'Inizio sosta' : 'Fine sosta'}</span>
{displayStopEvent.inferred && <p className="stop-inferred">Stimata dalle posizioni GPS: verifica orari e durata.</p>}
<h2>{formatDate(displayStopEvent.observedAt)}</h2>
<dl className="inspector-data"><div><dt>Durata sosta</dt><dd>{stopDuration(events, displayStopEvent)}</dd></div></dl>
</section>}
{displayPoint ? <>
<h2>
{formatDate(displayPoint.observedAt)}
</h2>
<div className="inspector-coordinates">
{Math.abs(displayPoint.latitude).toFixed(6)}° {displayPoint.latitude >= 0 ? 'N' : 'S'}
<br />
{Math.abs(displayPoint.longitude).toFixed(6)}° {displayPoint.longitude >= 0 ? 'E' : 'O'}
</div>
<dl className="inspector-data">
<div>
<dt>Velocità</dt>
<dd>
{displayPoint.speedMps != null ? `${(displayPoint.speedMps * 3.6).toFixed(1)} km/h` : '—'}
</dd>
</div>
<div>
<dt>Altitudine</dt>
<dd>
{displayPoint.altitudeM != null ? `${displayPoint.altitudeM.toFixed(0)} m` : '—'}
</dd>
</div>
<div>
<dt>Tempo dal precedente</dt>
<dd>
{displayPoint.deltaSec != null ? `${displayPoint.deltaSec.toFixed(1)} s` : '—'}
</dd>
</div>
<div>
<dt>Distanza dal precedente</dt>
<dd>
{formatDistance(displayPoint.deltaM)}
</dd>
</div>
<div>
<dt>Precisione GPS</dt>
<dd>
{displayPoint.horizontalAccuracyM != null ? `${displayPoint.horizontalAccuracyM.toFixed(1)} m` : '—'}
</dd>
</div>
</dl>
<div className="nearby-head">TELEMETRIA VICINA</div>
{nearbyLoading ? <p className="inspector-muted">Caricamento segnali…</p> : nearby.length ? <div className="nearby-list">
{nearby.map((item, index) => <div key={`${item.name}-${index}`}>
<span>
{catalog.find(metric => metric.name === item.name)?.description || item.name.replaceAll('.', ' · ')}
</span>
<strong>
{item.value.toLocaleString('it-IT', { maximumFractionDigits: 2 })} {catalog.find(metric => metric.name === item.name)?.unit ?? item.unit}
</strong>
</div>)}
</div> : <p className="inspector-muted">Nessun segnale vicino al punto.</p>}
</> : <div className="inspector-empty">
<span>◎</span>
<p>{displayStopEvent ? 'Passa sulla traccia o seleziona un punto GPS per vedere velocità e telemetria.' : 'Passa il mouse sulla traccia o seleziona un punto per ispezionarlo.'}</p>
</div>}
</aside>
<section className="chart-section">
<div className="section-head">
<div>
<span className="eyebrow">SERIE TEMPORALE</span>
<h2>Segnali del mezzo</h2>
</div>
<div className="metric-actions"><select className="metric-selector" value={metricName} onChange={e => { setMetricName(e.target.value); setEditingMetric(false); setMetricMessage('') }} aria-label="Seleziona segnale">
{catalog.length === 0 && <option value="">Nessun segnale</option>}{catalog.map(m => <option key={m.name} value={m.name}>
{metricLabel(m)} ({m.unit || '—'})</option>)}
</select><button className="outline-button" type="button" onClick={openMetricEditor} disabled={!selectedMetric}>Nome e unità</button></div>
</div>
{selectedMetric && <p className="metric-identity">ID segnale: <code>{selectedMetric.name}</code>. Nome e unità modificano la visualizzazione; per PID raw il valore resta grezzo.</p>}
{editingMetric && selectedMetric && <form className="metric-editor" onSubmit={saveMetric}>
<label>Nome leggibile<input value={displayName} onChange={event => setDisplayName(event.target.value)} maxLength={128} required /></label>
<label>Unità<input value={unit} onChange={event => setUnit(event.target.value)} maxLength={32} placeholder="es. kPa, °C; vuoto se sconosciuta" /></label>
<button className="primary-button" type="submit" disabled={metricBusy}>{metricBusy ? 'Salvataggio…' : 'Salva metrica'}</button>
<button className="outline-button" type="button" onClick={() => setEditingMetric(false)}>Annulla</button>
</form>}
{metricMessage && <p className="metric-message" role="status">{metricMessage}</p>}
{catalog.length ? <TelemetryChart samples={metrics} unit={selectedMetric?.unit || ''} selectedAt={displayPoint?.observedAt} onHoverAt={at => setHoverPoint(at ? nearestGps(gps, at) : null)} onSelectAt={at => { const point = nearestGps(gps, at); if (point) selectPoint(point) }} /> : <div className="empty-message">Nessun segnale per questo viaggio.</div>}
</section>
</div>
</div>}
</div>
</>
  )
}

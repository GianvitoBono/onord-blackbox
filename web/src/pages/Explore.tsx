import type { Dispatch, SetStateAction } from 'react'
import type { GpsSample, MetricDefinition, MetricSample, Trip, TripEvent } from '../api'
import TripMap from '../components/TripMap'
import TelemetryChart from '../components/TelemetryChart'
import { formatDate, formatDistance, metricLabel, nearestGps, tripReason, type NearbyReading } from './format'

type Props = { trips: Trip[]; tripId: string; setTripId: Dispatch<SetStateAction<string>>; selectedTrip?: Trip; detailError: string; detailLoading: boolean; onRefresh: () => void; gps: GpsSample[]; events: TripEvent[]; displayPoint: GpsSample | null; setHoverPoint: Dispatch<SetStateAction<GpsSample | null>>; setSelectedPoint: Dispatch<SetStateAction<GpsSample | null>>; displayStopEvent: TripEvent | null; setHoverStopEvent: Dispatch<SetStateAction<TripEvent | null>>; setSelectedStopEvent: Dispatch<SetStateAction<TripEvent | null>>; nearby: NearbyReading[]; nearbyLoading: boolean; catalog: MetricDefinition[]; metricName: string; setMetricName: Dispatch<SetStateAction<string>>; metrics: MetricSample[]; selectedMetric?: MetricDefinition }

function stopDuration(events: TripEvent[], event: TripEvent): string {
  const match = events.find((candidate) => candidate.stopId === event.stopId && candidate.kind !== event.kind)
  if (!match) return event.kind === 'stop_start' ? 'Sosta in corso' : 'Inizio sosta non registrato'
  const seconds = Math.max(0, Math.round(Math.abs(Date.parse(match.observedAt) - Date.parse(event.observedAt)) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours ? `${hours} h ${minutes} min` : `${minutes} min`
}

export default function Explore({ trips, tripId, setTripId, selectedTrip, detailError, detailLoading, onRefresh, gps, events, displayPoint, setHoverPoint, setSelectedPoint, displayStopEvent, setHoverStopEvent, setSelectedStopEvent, nearby, nearbyLoading, catalog, metricName, setMetricName, metrics, selectedMetric }: Props) {
  return (
    <>
<div className="page-heading explore-heading">
<div>
<span className="eyebrow">ANALISI PERCORSO</span>
<h1>Esplora</h1>
<p>Mappa e segnali sincronizzati. Passa sui punti per vedere i dati.</p>
</div>
<div className="explore-controls"><button className="outline-button" type="button" onClick={onRefresh} disabled={detailLoading}>{detailLoading ? 'Aggiornamento…' : '↻ Aggiorna dati'}</button><select className="trip-selector" aria-label="Seleziona viaggio" value={tripId} onChange={e => setTripId(e.target.value)}>
{trips.length === 0 && <option value="">Nessun viaggio</option>}{trips.map(t => <option key={t.id} value={t.id}>
{formatDate(t.startedAt)} · {formatDistance(t.distanceGpsM ?? t.distanceObdM)}
</option>)}
</select></div>
</div>
{detailError && <div className="banner error">
{detailError}
</div>}
<div className="explore-layout">
<div className="map-column">
<div className="map-heading">
<div>
<span className="eyebrow">TRACCIA GPS</span>
<h2>
{selectedTrip ? formatDate(selectedTrip.startedAt) : 'Seleziona un viaggio'}
</h2>
{selectedTrip && <p>Fine: {selectedTrip.endedAt ? formatDate(selectedTrip.endedAt) : 'in corso'} · Avvio: {tripReason(selectedTrip.startReason)} · Chiusura: {tripReason(selectedTrip.endReason)}</p>}
</div>
<span>
{`${gps.length} punti`}
</span>
</div>
<div className="large-map">
<TripMap samples={gps} events={events} selectedSampleId={displayPoint?.sampleId} onPointHover={setHoverPoint} onPointSelect={setSelectedPoint} onEventHover={setHoverStopEvent} onEventSelect={setSelectedStopEvent} />
</div>
</div>
<aside className="point-inspector">
<span className="eyebrow">PUNTO SELEZIONATO</span>
{displayStopEvent && <section className="stop-inspector" aria-label="Evento sosta selezionato">
<span className={`stop-event-kind ${displayStopEvent.kind === 'stop_start' ? 'is-start' : 'is-end'}`}>{displayStopEvent.kind === 'stop_start' ? 'Inizio sosta' : 'Fine sosta'}</span>
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
{item.name.replaceAll('.', ' · ')}
</span>
<strong>
{item.value.toLocaleString('it-IT', { maximumFractionDigits: 2 })} {item.unit}
</strong>
</div>)}
</div> : <p className="inspector-muted">Nessun segnale vicino al punto.</p>}
</> : <div className="inspector-empty">
<span>◎</span>
<p>{displayStopEvent ? 'Passa sulla traccia o seleziona un punto GPS per vedere velocità e telemetria.' : 'Passa il mouse sulla traccia o seleziona un punto per ispezionarlo.'}</p>
</div>}
</aside>
</div>
<section className="chart-section">
<div className="section-head">
<div>
<span className="eyebrow">SERIE TEMPORALE</span>
<h2>Segnali del mezzo</h2>
</div>
<select className="metric-selector" value={metricName} onChange={e => setMetricName(e.target.value)} aria-label="Seleziona segnale">
{catalog.length === 0 && <option value="">Nessun segnale</option>}{catalog.map(m => <option key={m.name} value={m.name}>
{metricLabel(m)} ({m.unit || '—'})</option>)}
</select>
</div>
{catalog.length ? <TelemetryChart samples={metrics} unit={selectedMetric?.unit || ''} selectedAt={displayPoint?.observedAt} onHoverAt={at => setHoverPoint(at ? nearestGps(gps, at) : null)} onSelectAt={at => { const point = nearestGps(gps, at); if (point) setSelectedPoint(point) }} /> : <div className="empty-message">Nessun segnale per questo viaggio.</div>}
</section>
</>
  )
}

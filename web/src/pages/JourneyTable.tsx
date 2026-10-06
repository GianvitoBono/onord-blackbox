import type { Trip } from '../api'
import { formatDate, formatDistance, tripReason } from './format'

export default function JourneyTable({ trips, openTrip, loading }: { trips: Trip[]; openTrip: (id: string) => void; loading: boolean }) { return <div className="table-shell">
<table className="journey-table">
<thead>
<tr>
<th>VIAGGIO</th>
<th>INIZIO</th>
<th>FINE</th>
<th>DURATA</th>
<th>DISTANZA</th>
<th>STATO</th>
<th />
</tr>
</thead>
<tbody>
{trips.map((trip, index) => { const duration = trip.endedAt ? Math.max(0, Math.round((Date.parse(trip.endedAt) - Date.parse(trip.startedAt)) / 60000)) : null; return <tr key={trip.id}>
<td>
<span className="trip-index">
{String(index + 1).padStart(2, '0')}
</span>
<strong>Viaggio {formatDate(trip.startedAt)}
</strong>
<small className="trip-reason">Avvio: {tripReason(trip.startReason)}</small>
</td>
<td>
{formatDate(trip.startedAt)}
</td>
<td>{trip.endedAt ? <>{formatDate(trip.endedAt)}<small className="trip-reason">{tripReason(trip.endReason)}</small></> : '—'}</td>
<td>
{duration == null ? 'In corso' : `${Math.floor(duration / 60)}h ${String(duration % 60).padStart(2, '0')}m`}
</td>
<td>
{formatDistance(trip.distanceGpsM ?? trip.distanceObdM)}
</td>
<td>
<span className={`trip-state ${trip.endedAt ? '' : 'running'}`}>
{trip.endedAt ? 'Completato' : 'In corso'}
</span>
</td>
<td>
<button className="row-action" onClick={() => openTrip(trip.id)} aria-label={`Esplora viaggio ${formatDate(trip.startedAt)}`}>Esplora ↗</button>
</td>
</tr> })}
</tbody>
</table>
{!trips.length && <div className="empty-message">
{loading ? 'Caricamento viaggi…' : 'Nessun viaggio registrato per questo mezzo.'}
</div>}
</div> }

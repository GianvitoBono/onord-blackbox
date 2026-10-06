import type { Trip, Vehicle } from '../api'
import { time, formatDistance } from './format'
import JourneyTable from './JourneyTable'

type Props = { selectedVehicle?: Vehicle; trips: Trip[]; totalDistance: number; detailLoading: boolean; deviceFresh: boolean; updatedAt: Date | null; loading: boolean; loadFleet: () => Promise<void>; openTrip: (id: string) => void }

export default function Overview({ selectedVehicle, trips, totalDistance, detailLoading, deviceFresh, updatedAt, loading, loadFleet, openTrip }: Props) {
  return (
    <>
<div className="page-heading">
<div>
<span className="eyebrow">CONTROLLO OPERATIVO</span>
<h1>Panoramica</h1>
<p>Stato e attività di {selectedVehicle?.displayName || 'tua flotta'}.</p>
</div>
<button className="outline-button" onClick={() => void loadFleet()} disabled={loading}>
{loading ? 'Aggiornamento…' : '↻ Aggiorna'}
</button>
</div>
<div className="overview-hero">
<div className="hero-main">
<span className="eyebrow">MEZZO SELEZIONATO</span>
<h2>
{selectedVehicle?.displayName || 'Nessun mezzo'}
</h2>
<p>
{[selectedVehicle?.modelYear, selectedVehicle?.make, selectedVehicle?.model].filter(Boolean).join(' ') || 'Dettagli mezzo non disponibili'}
</p>
<div className="hero-actions">
<a href="#explore" className="primary-button">Esplora percorso →</a>
<a href="#fleet" className="light-link">Stato mezzo ↗</a>
</div>
</div>
<div className="hero-stat">
<small>VIAGGI RECENTI</small>
<strong>
{trips.length}
</strong>
<span>ultimi 30 registrati</span>
</div>
<div className="hero-stat">
<small>DISTANZA RILEVATA</small>
<strong>
{formatDistance(totalDistance)}
</strong>
<span>nei viaggi visibili</span>
</div>
</div>
<div className="section-head">
<div>
<span className="eyebrow">ULTIMA ATTIVITÀ</span>
<h2>Viaggi recenti</h2>
</div>
<a href="#journeys">Tutti i viaggi ↗</a>
</div>
<JourneyTable trips={trips.slice(0, 5)} openTrip={openTrip} loading={detailLoading} />
<div className="overview-foot">
<span>
<i className="live-dot" /> {deviceFresh ? 'Dati ricevuti di recente' : 'Nessun dato recente'}
</span>
<span>Ultimo aggiornamento {updatedAt ? time.format(updatedAt) : '—'}
</span>
</div>
</>
  )
}

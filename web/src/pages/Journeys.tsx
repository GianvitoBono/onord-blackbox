import type { Trip, Vehicle } from '../api'
import JourneyTable from './JourneyTable'

type Props = { selectedVehicle?: Vehicle; trips: Trip[]; detailLoading: boolean; openTrip: (id: string) => void }

export default function Journeys({ selectedVehicle, trips, detailLoading, openTrip }: Props) {
  return (
    <>
<div className="page-heading">
<div>
<span className="eyebrow">ARCHIVIO PERCORSI</span>
<h1>Viaggi</h1>
<p>
{selectedVehicle?.displayName || 'Mezzo'} · {trips.length} viaggi registrati</p>
</div>
</div>
<JourneyTable trips={trips} openTrip={openTrip} loading={detailLoading} />
</>
  )
}

import type { Dispatch, SetStateAction, FormEvent } from 'react'
import type { ManagedDevice, Vehicle, VehicleEcuIdentity, VehicleStatus } from '../api'
import { formatDate, metricLabel } from './format'

type VehicleForm = { displayName: string; make: string; model: string; modelYear: string }
type Props = { vehicles: Vehicle[]; devices: ManagedDevice[]; selectedId: string; selectedVehicle?: Vehicle; selectedDevice?: ManagedDevice; deviceFresh: boolean; vehicleStatus: VehicleStatus | null; statusError: string; ecuIdentity: VehicleEcuIdentity | null; ecuIdentityError: string; vehicleMessage: string; vehicleEditing: string | null; vehicleForm: VehicleForm; setVehicleForm: Dispatch<SetStateAction<VehicleForm>>; vehicleBusy: boolean; saveVehicle: (event: FormEvent) => Promise<void>; editVehicle: (vehicle?: Vehicle) => void; setVehicleEditing: Dispatch<SetStateAction<string | null>>; selectVehicle: (id: string) => void; openTrip: (id: string) => void; now: number }

const freshness = (timestamp: string | undefined, now: number) => {
  if (!timestamp) return 'Orario non disponibile'
  const age = now - Date.parse(timestamp)
  if (!Number.isFinite(age)) return 'Orario non valido'
  return age <= 10 * 60_000 ? 'Recente' : 'Obsoleto'
}
const ecuLabels: Record<string, string> = { '0904': 'ID calibrazione', '0906': 'CVN', '090A': 'Nome ECU' }
const metricSource = (name: string) => name.startsWith('device.') ? 'Telefono' : name.startsWith('obd.calc.') ? 'Calcolato' : 'OBD'

export default function Fleet({ vehicles, devices, selectedId, selectedVehicle, selectedDevice, deviceFresh, vehicleStatus, statusError, ecuIdentity, ecuIdentityError, vehicleMessage, vehicleEditing, vehicleForm, setVehicleForm, vehicleBusy, saveVehicle, editVehicle, setVehicleEditing, selectVehicle, openTrip, now }: Props) {
  return (
    <>
<div className="page-heading">
<div>
<span className="eyebrow">PARCO MEZZI</span>
<h1>Flotta</h1>
<p>
{vehicles.length} mezzi associati al tuo account.</p>
</div>
<button className="primary-button" onClick={() => editVehicle()}>+ Aggiungi mezzo</button>
</div>
{vehicleMessage && <div className="banner" role="status">
{vehicleMessage}
</div>}{vehicleEditing && <form className="vehicle-editor" onSubmit={saveVehicle}>
<div>
<span className="eyebrow">FLOTTA</span>
<h2>
{vehicleEditing === 'new' ? 'Nuovo mezzo' : 'Modifica mezzo'}
</h2>
</div>
<label>Nome<input required maxLength={120} value={vehicleForm.displayName} onChange={e => setVehicleForm(v => ({ ...v, displayName: e.target.value }))} />
</label>
<label>Marca<input maxLength={120} value={vehicleForm.make} onChange={e => setVehicleForm(v => ({ ...v, make: e.target.value }))} />
</label>
<label>Modello<input maxLength={120} value={vehicleForm.model} onChange={e => setVehicleForm(v => ({ ...v, model: e.target.value }))} />
</label>
<label>Anno<input type="number" min="1900" max="2100" value={vehicleForm.modelYear} onChange={e => setVehicleForm(v => ({ ...v, modelYear: e.target.value }))} />
</label>
<button className="primary-button" disabled={vehicleBusy}>
{vehicleBusy ? 'Salvataggio…' : 'Salva mezzo'}
</button>
<button type="button" className="quiet-button" onClick={() => setVehicleEditing(null)}>Annulla</button>
</form>}
<div className="fleet-list">
{vehicles.map(v => { const related = devices.filter(d => d.vehicleId === v.id); return <button key={v.id} className={`fleet-row ${v.id === selectedId ? 'selected' : ''}`} onClick={() => selectVehicle(v.id)}>
<span className="fleet-icon">↗</span>
<span className="fleet-identity">
<strong>
{v.displayName}
</strong>
<small>
{[v.modelYear, v.make, v.model].filter(Boolean).join(' ') || v.id}
</small>
</span>
<span className="fleet-devices">
{related.length} {related.length === 1 ? 'dispositivo' : 'dispositivi'}
</span>
<span className="fleet-action">
{v.id === selectedId ? 'Selezionato' : 'Seleziona →'}
</span>
</button>; })}{vehicles.length === 0 && <div className="empty-message">Nessun mezzo disponibile.</div>}
</div>
{selectedVehicle && <div className="fleet-detail">
<div>
<span className="eyebrow">STATO MEZZO</span>
<h2>
{selectedVehicle.displayName}
</h2>
<button className="outline-button" onClick={() => editVehicle(selectedVehicle)}>Modifica mezzo</button>
<p>ID mezzo <code>
{selectedVehicle.id}
</code>
</p>
</div>
<dl>
<div>
<dt>Dispositivo corrente</dt>
<dd>
{selectedVehicle.currentDeviceId || 'Non assegnato'}
</dd>
</div>
<div>
<dt>Connessione</dt>
<dd>
{deviceFresh ? 'Dati recenti' : 'Nessun dato recente'}
</dd>
</div>
<div>
<dt>Ultimo segnale</dt>
<dd>
{formatDate(selectedDevice?.lastReceivedAt)}
</dd>
</div>
</dl>
</div>}
{selectedVehicle && <section className="fleet-telemetry">
  <div className="section-head">
    <div><span className="eyebrow">DATI RICEVUTI</span><h2>Telemetria del veicolo</h2></div>
    {vehicleStatus?.tripId && <button className="row-action" onClick={() => openTrip(vehicleStatus.tripId!)}>Esplora viaggio ↗</button>}
  </div>
  {statusError && <p className="inspector-muted">{statusError}</p>}
  {!vehicleStatus && !statusError && <p className="inspector-muted">Caricamento stato…</p>}
  {vehicleStatus?.gps ? <div className="fleet-position">
    <span>GPS · sorgente posizione · {formatDate(vehicleStatus.gps.observedAt)} · {freshness(vehicleStatus.gps.observedAt, now)}</span>
    <strong>{Math.abs(vehicleStatus.gps.latitude).toFixed(5)}° {vehicleStatus.gps.latitude >= 0 ? 'N' : 'S'} · {Math.abs(vehicleStatus.gps.longitude).toFixed(5)}° {vehicleStatus.gps.longitude >= 0 ? 'E' : 'O'}</strong>
    <span>{vehicleStatus.gps.speedMps == null ? 'Velocità non disponibile' : `${(vehicleStatus.gps.speedMps * 3.6).toFixed(1)} km/h`}</span>
  </div> : vehicleStatus && <p className="inspector-muted">Nessun punto GPS disponibile.</p>}
  {vehicleStatus?.metrics.length ? <div className="fleet-metrics">
    {vehicleStatus.metrics.map(metric => <div key={metric.name}>
      <span>{metricLabel({ name: metric.name, unit: metric.unit })}<small>{metricSource(metric.name)} · {formatDate(metric.observedAt)} · {freshness(metric.observedAt, now)}</small></span>
      <strong>{metric.value.toLocaleString('it-IT', { maximumFractionDigits: 2 })} {metric.unit}</strong>
    </div>)}
  </div> : vehicleStatus && <p className="inspector-muted">Nessun segnale disponibile.</p>}
</section>}
{selectedVehicle && <section className="fleet-telemetry ecu-panel">
  <div className="section-head"><div><span className="eyebrow">DIAGNOSTICA · MODE 09</span><h2>Identificazione ECU</h2></div></div>
  {ecuIdentityError && <p className="inspector-muted">{ecuIdentityError}</p>}
  {!ecuIdentity && !ecuIdentityError && <p className="inspector-muted">Nessun report ECU ricevuto.</p>}
  {ecuIdentity && <>
    <div className="ecu-meta"><span>Dispositivo <code>{ecuIdentity.deviceId}</code></span><span>Rilevato {formatDate(ecuIdentity.observedAt)} · ricevuto {formatDate(ecuIdentity.receivedAt)}</span></div>
    <div className="ecu-meta"><span>Adattatore <strong>{ecuIdentity.report.adapter || '—'}</strong></span><span>Protocollo <strong>{[ecuIdentity.report.protocol_number, ecuIdentity.report.protocol_name].filter(Boolean).join(' · ') || '—'}</strong></span></div>
    {ecuIdentity.report.error && <p className="inspector-muted">{ecuIdentity.report.error}</p>}
    {(ecuIdentity.report.reads || []).filter(read => ['0904', '0906', '090A'].includes(read.command)).map(read => {
      const entries = Object.entries(read.values_by_ecu || {})
      return <div className="ecu-read" key={read.command}><strong>{ecuLabels[read.command] || read.command}</strong>
        {entries.length ? entries.map(([ecu, value]) => <div className="ecu-value" key={ecu}><code>{ecu}</code><span>{value}</span></div>) : <span className="inspector-muted">{read.detail || read.status}</span>}
      </div>
    })}
    <details className="ecu-raw"><summary>Mostra report grezzo</summary><pre>{JSON.stringify(ecuIdentity.report, null, 2)}</pre></details>
  </>}
</section>}
</>
  )
}

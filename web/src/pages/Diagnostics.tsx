import type { DiagnosticTroubleCode, Vehicle, VehicleDiagnosticReport } from '../api'
import { formatDate } from './format'

type Props = { vehicle?: Vehicle; reports: VehicleDiagnosticReport[]; selected: number; setSelected: (index: number) => void; loading: boolean; error: string; refresh: () => void }
const groups = [ ['stored', 'Memorizzati'], ['pending', 'In attesa'], ['permanent', 'Permanenti'] ] as const

function values(value: unknown): DiagnosticTroubleCode[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (typeof item === 'string') return [{ code: item }]
    if (!item || typeof item !== 'object') return []
    const row = item as Record<string, unknown>
    const code = row.code ?? row.dtc ?? row.value
    return typeof code === 'string' ? [{ code, status: typeof row.status === 'string' ? row.status : undefined, raw: typeof row.raw === 'string' ? row.raw : undefined, ecu: typeof row.ecu === 'string' ? row.ecu : undefined, description: typeof row.description === 'string' ? row.description : undefined }] : []
  })
}
function reportCodes(row: VehicleDiagnosticReport, key: typeof groups[number][0]) {
  const direct = values(row[key])
  if (direct.length) return direct
  const nested = row.report as Record<string, unknown> | undefined
  const status = nested?.[`${key}_codes`] as { value?: unknown } | undefined
  return values(status?.value)
}
function reportState(row: VehicleDiagnosticReport, key: typeof groups[number][0]) {
  const nested = row.report as Record<string, unknown> | undefined
  const query = nested?.[`${key}_codes`] as { status?: unknown; detail?: unknown } | undefined
  return { status: typeof query?.status === 'string' ? query.status : null, detail: typeof query?.detail === 'string' ? query.detail : null }
}
function milState(row: VehicleDiagnosticReport) {
  if (typeof row.milOn === 'boolean') return row.milOn
  const status = (row.report as Record<string, any> | undefined)?.emissions_status?.value?.mil_on
  return typeof status === 'boolean' ? status : null
}

export default function Diagnostics({ vehicle, reports, selected, setSelected, loading, error, refresh }: Props) {
  const row = reports[selected] || reports[0]
  const mil = row ? milState(row) : null
  const total = row ? groups.reduce((n, [key]) => n + reportCodes(row, key).length, 0) : 0
  const readable = row && groups.some(([key]) => reportState(row, key).status === 'success')
  return <>
    <div className="page-heading">
      <div><span className="eyebrow">OBD · CENTRALINA MOTORE</span><h1>Diagnostica</h1><p>Codici guasto rilevati da {vehicle?.displayName || 'mezzo selezionato'}.</p></div>
      <button className="outline-button" onClick={refresh} disabled={loading}>{loading ? 'Aggiornamento…' : '↻ Aggiorna'}</button>
    </div>
    {error && <div className="banner error" role="alert">{error}</div>}
    {reports.length > 0 && <div className="diagnostics-toolbar"><label htmlFor="diagnostic-report">Lettura</label><select id="diagnostic-report" className="metric-selector" value={selected} onChange={e => setSelected(Number(e.target.value))}>{reports.map((item, i) => <option key={`${item.deviceId}-${item.observedAt}-${i}`} value={i}>{formatDate(item.observedAt)}{i === 0 ? ' · più recente' : ''}</option>)}</select><span>{reports.length} lettur{reports.length === 1 ? 'a' : 'e'} disponibili</span></div>}
    {!row ? <section className="diagnostics-empty"><span className="eyebrow">NESSUNA LETTURA</span><h2>{loading ? 'Caricamento diagnostica…' : 'Nessun report DTC disponibile'}</h2><p>Quando l’app leggerà i codici dalla centralina e sincronizzerà il report, i risultati appariranno qui.</p></section> : <>
      <section className="diagnostics-summary">
        <div><span className="eyebrow">ULTIMA LETTURA</span><strong>{formatDate(row.observedAt)}</strong><small>Ricevuta {formatDate(row.receivedAt)}</small></div>
        <div className={`mil-indicator ${mil === true ? 'mil-on' : mil === false ? 'mil-off' : ''}`}><span className="mil-lamp"/><span><b>{mil === null ? 'MIL non disponibile' : mil ? 'Spia motore accesa' : 'Spia motore spenta'}</b><small>Stato spia di avaria</small></span></div>
        <div className="dtc-total"><strong>{readable ? row.dtcCount ?? total : '—'}</strong><span>{readable ? 'codici guasto letti' : 'codici non disponibili'}</span></div>
      </section>
      <div className="diagnostics-groups">{groups.map(([key, title]) => { const codes = reportCodes(row, key); const state = reportState(row, key); return <section className="diagnostic-group" key={key}><div className="section-head"><div><span className="eyebrow">{key === 'stored' ? 'DTC 03' : key === 'pending' ? 'DTC 07' : 'DTC 0A'}</span><h2>{title}</h2></div><span className="count-pill">{state.status === 'success' ? codes.length : '—'}</span></div>{codes.length ? <ul>{codes.map((dtc, i) => <li key={`${dtc.code}-${i}`}><code>{dtc.code}</code><div><strong>{dtc.description || 'Descrizione non disponibile'}</strong>{dtc.ecu && <small>ECU {dtc.ecu}</small>}{dtc.status && <small>{dtc.status}</small>}</div>{dtc.raw && <details><summary>Raw</summary><code>{dtc.raw}</code></details>}</li>)}</ul> : <p className="diagnostic-none">{state.status === 'success' ? 'Nessun codice in questa categoria.' : `Lettura non disponibile${state.detail ? `: ${state.detail}` : '.'}`}</p>}</section> })}</div>
      <details className="ecu-raw diagnostics-raw"><summary>Dettagli grezzi del report</summary><pre>{JSON.stringify(row.report, null, 2)}</pre></details>
    </>}
  </>
}

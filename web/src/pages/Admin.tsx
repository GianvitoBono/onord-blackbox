import type { Dispatch, SetStateAction } from 'react'
import type { ManagedDevice, PendingDevice, Vehicle } from '../api'
import { formatDate } from './format'

type Props = { vehicles: Vehicle[]; devices: ManagedDevice[]; pending: PendingDevice[]; adminMessage: string; adminVehicle: Record<string, string>; setAdminVehicle: Dispatch<SetStateAction<Record<string, string>>>; adminName: Record<string, string>; setAdminName: Dispatch<SetStateAction<Record<string, string>>>; adminPassword: Record<string, string>; setAdminPassword: Dispatch<SetStateAction<Record<string, string>>>; adminBusy: string; tokenMode: Record<string, 'generated' | 'custom'>; setTokenMode: Dispatch<SetStateAction<Record<string, 'generated' | 'custom'>>>; customToken: Record<string, string>; setCustomToken: Dispatch<SetStateAction<Record<string, string>>>; issuedToken: { deviceId: string; token: string } | null; approve: (row: PendingDevice) => Promise<void>; rotate: (device: ManagedDevice) => Promise<void>; loadFleet: () => Promise<void> }

export default function Admin({ vehicles, devices, pending, adminMessage, adminVehicle, setAdminVehicle, adminName, setAdminName, adminPassword, setAdminPassword, adminBusy, tokenMode, setTokenMode, customToken, setCustomToken, issuedToken, approve, rotate, loadFleet }: Props) {
  return (
    <>
<div className="page-heading">
<div>
<span className="eyebrow">CONTROLLO ACCESSI</span>
<h1>Admin</h1>
<p>Approva dispositivi e gestisci i token di connessione.</p>
</div>
<button className="outline-button" onClick={() => void loadFleet()}>↻ Aggiorna</button>
</div>
{adminMessage && <div className="banner" role="status">
{adminMessage}
</div>}
<div className="section-head">
<div>
<span className="eyebrow">RICHIESTE</span>
<h2>Connessioni fallite <span className="count-pill">
{pending.length}
</span>
</h2>
</div>
</div>
{pending.length ? <div className="admin-list">
{pending.map(row => <article className="admin-row" key={row.deviceId}>
<div className="admin-row-head">
<div>
<span className="eyebrow">
{row.reason.replaceAll('_', ' ')}
</span>
<h3>
{row.displayName || 'Dispositivo non riconosciuto'}
</h3>
<code>
{row.deviceId}
</code>
</div>
<div className="admin-meta">
{row.attemptCount} tentativi<br />Ultimo: {formatDate(row.lastSeenAt)}
</div>
</div>
{row.approvable ? <div className="admin-form">
<label>Mezzo<select value={adminVehicle[row.deviceId] || ''} onChange={e => setAdminVehicle(v => ({ ...v, [row.deviceId]: e.target.value }))}>
<option value="">Seleziona</option>
{vehicles.map(v => <option value={v.id} key={v.id}>
{v.displayName}
</option>)}
</select>
</label>
<label>Nome dispositivo<input value={adminName[row.deviceId] || ''} onChange={e => setAdminName(v => ({ ...v, [row.deviceId]: e.target.value }))} placeholder="Nome riconoscibile" />
</label>
<label>Password account<input type="password" autoComplete="current-password" value={adminPassword[row.deviceId] || ''} onChange={e => setAdminPassword(v => ({ ...v, [row.deviceId]: e.target.value }))} />
</label>
<button className="primary-button" disabled={adminBusy === row.deviceId} onClick={() => void approve(row)}>
{adminBusy === row.deviceId ? 'Approvazione…' : 'Approva'}
</button>
<p>Verifica ID sul telefono prima di approvare. Verrà autorizzato hash del token dell’ultimo tentativo.</p>
</div> : <p className="admin-guidance">
{row.reason === 'token_mismatch' ? 'Token non corrispondente: ruotalo sul dispositivo registrato qui sotto.' : 'Questa richiesta non può essere approvata. Verifica formato del token sul telefono o stato del dispositivo.'}
</p>}
</article>)}
</div> : <div className="empty-message">Nessuna connessione fallita registrata.</div>}
<div className="section-head admin-second">
<div>
<span className="eyebrow">CREDENZIALI</span>
<h2>Dispositivi registrati</h2>
</div>
</div>
<div className="admin-list">
{devices.map(device => <article className="admin-row" key={device.id}>
<div className="admin-row-head">
<div>
<span className="eyebrow">
{vehicles.find(v => v.id === device.vehicleId)?.displayName || 'Mezzo'}
</span>
<h3>
{device.displayName}
</h3>
<code>
{device.id}
</code>
</div>
<span className="status-chip">
{device.tokenRevokedAt ? 'Revocato' : 'Attivo'}
</span>
</div>
<div className="admin-form">
<label>Nuovo token<select value={tokenMode[device.id] || 'generated'} onChange={e => setTokenMode(v => ({ ...v, [device.id]: e.target.value as 'generated' | 'custom' }))}>
<option value="generated">Genera automaticamente</option>
<option value="custom">Imposta manualmente</option>
</select>
</label>
{tokenMode[device.id] === 'custom' && <label>Token personalizzato<input autoComplete="off" value={customToken[device.id] || ''} onChange={e => setCustomToken(v => ({ ...v, [device.id]: e.target.value }))} placeholder="16–128 caratteri" />
</label>}
<label>Password account<input type="password" autoComplete="current-password" value={adminPassword[device.id] || ''} onChange={e => setAdminPassword(v => ({ ...v, [device.id]: e.target.value }))} />
</label>
<button className="outline-button" disabled={!!device.tokenRevokedAt || adminBusy === device.id} onClick={() => void rotate(device)}>
{adminBusy === device.id ? 'Rotazione…' : 'Ruota token'}
</button>
</div>
{issuedToken?.deviceId === device.id && <div className="issued-token">
<strong>Nuovo token — copialo ora</strong>
<div>
<input readOnly value={issuedToken.token} onFocus={e => e.currentTarget.select()} />
<button onClick={() => void navigator.clipboard.writeText(issuedToken.token)}>Copia</button>
</div>
</div>}
</article>)}
</div>
</>
  )
}

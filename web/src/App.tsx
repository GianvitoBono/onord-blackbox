import { FormEvent, useCallback, useEffect, useState } from 'react'
import { ApiError, approvePendingDevice, fetchDevices, fetchPendingDevices, fetchSession, fetchTripEvents, fetchTripGps, createVehicle, updateVehicle, fetchTripMetricCatalog, fetchTripMetricSamples, fetchTripTelemetryAt, fetchTrips, fetchVehicles, fetchVehicleStatus, GpsSample, login, logout, ManagedDevice, MetricDefinition, MetricSample, PendingDevice, rotateDeviceToken, Trip, TripEvent, Vehicle, VehicleStatus } from './api'
import Overview from './pages/Overview'
import Journeys from './pages/Journeys'
import Explore from './pages/Explore'
import Fleet from './pages/Fleet'
import Admin from './pages/Admin'
import Settings from './pages/Settings'
import { message, pages, pageFromHash, labels, glyphs, type Page, type NearbyReading } from './pages/format'

export default function App() {
  const [page, setPage] = useState<Page>(pageFromHash)
  const [theme, setTheme] = useState<'light' | 'dark'>(() => document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light')
  const [authReady, setAuthReady] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [signedIn, setSignedIn] = useState('')
  const [authBusy, setAuthBusy] = useState(false)
  const [authError, setAuthError] = useState('')
  const [vehicles, setVehicles] = useState<Vehicle[]>([])
  const [selectedId, setSelectedId] = useState(() => localStorage.getItem('opnord.vehicle') || '')
  const [devices, setDevices] = useState<ManagedDevice[]>([])
  const [vehicleStatus, setVehicleStatus] = useState<VehicleStatus | null>(null)
  const [statusError, setStatusError] = useState('')
  const [pending, setPending] = useState<PendingDevice[]>([])
  const [vehicleEditing, setVehicleEditing] = useState<string | null>(null)
  const [vehicleForm, setVehicleForm] = useState({ displayName: '', make: '', model: '', modelYear: '' })
  const [vehicleBusy, setVehicleBusy] = useState(false)
  const [vehicleMessage, setVehicleMessage] = useState('')
  const [trips, setTrips] = useState<Trip[]>([])
  const [tripId, setTripId] = useState('')
  const [gps, setGps] = useState<GpsSample[]>([])
  const [tripEvents, setTripEvents] = useState<TripEvent[]>([])
  const [catalog, setCatalog] = useState<MetricDefinition[]>([])
  const [metricName, setMetricName] = useState('')
  const [metrics, setMetrics] = useState<MetricSample[]>([])
  const [selectedPoint, setSelectedPoint] = useState<GpsSample | null>(null)
  const [hoverPoint, setHoverPoint] = useState<GpsSample | null>(null)
  const [selectedStopEvent, setSelectedStopEvent] = useState<TripEvent | null>(null)
  const [hoverStopEvent, setHoverStopEvent] = useState<TripEvent | null>(null)
  const [nearby, setNearby] = useState<NearbyReading[]>([])
  const [nearbyLoading, setNearbyLoading] = useState(false)
  const [loading, setLoading] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [refreshTick, setRefreshTick] = useState(0)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null)
  const [adminPassword, setAdminPassword] = useState<Record<string, string>>({})
  const [adminVehicle, setAdminVehicle] = useState<Record<string, string>>({})
  const [adminName, setAdminName] = useState<Record<string, string>>({})
  const [adminBusy, setAdminBusy] = useState('')
  const [adminMessage, setAdminMessage] = useState('')
  const [tokenMode, setTokenMode] = useState<Record<string, 'generated' | 'custom'>>({})
  const [customToken, setCustomToken] = useState<Record<string, string>>({})
  const [issuedToken, setIssuedToken] = useState<{ deviceId: string; token: string } | null>(null)
  const [mobileMenu, setMobileMenu] = useState(false)
  const [now, setNow] = useState(Date.now())
  const selectedVehicle = vehicles.find(v => v.id === selectedId)
  const selectedDevice = devices.find(d => d.id === selectedVehicle?.currentDeviceId)
  const deviceFresh = selectedDevice?.lastReceivedAt ? now - Date.parse(selectedDevice.lastReceivedAt) < 10 * 60_000 : false
  const selectedTrip = trips.find(t => t.id === tripId)
  const selectedMetric = catalog.find(m => m.name === metricName)
  const displayPoint = hoverPoint || selectedPoint
  const displayStopEvent = hoverStopEvent || selectedStopEvent
  const totalDistance = trips.reduce((n, t) => n + (t.distanceGpsM ?? t.distanceObdM ?? 0), 0)

  const clearSession = useCallback(() => { setSignedIn(''); setVehicles([]); setDevices([]); setVehicleStatus(null); setPending([]); setTrips([]); setTripId(''); setGps([]); setTripEvents([]); setCatalog([]); setMetrics([]); setSelectedPoint(null); setHoverPoint(null); setSelectedStopEvent(null); setHoverStopEvent(null); setNearby([]); setIssuedToken(null); setAdminPassword({}); setCustomToken({}); setAdminName({}); setTokenMode({}); setPassword('') }, [])
  const handleError = useCallback((failure: unknown) => { if (failure instanceof ApiError && (failure.status === 401 || failure.status === 403)) clearSession(); else setError(message(failure)) }, [clearSession])
  const loadFleet = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [vehicleRows, deviceRows, pendingRows] = await Promise.all([fetchVehicles(), fetchDevices(), fetchPendingDevices()])
      setVehicles(vehicleRows); setDevices(deviceRows); setPending(pendingRows)
      setSelectedId(current => vehicleRows.some(v => v.id === current) ? current : vehicleRows[0]?.id || '')
      setAdminVehicle(current => Object.fromEntries(pendingRows.map(row => [row.deviceId, current[row.deviceId] || row.vehicleId || vehicleRows[0]?.id || ''])))
      setAdminName(current => Object.fromEntries(pendingRows.map(row => [row.deviceId, current[row.deviceId] || row.displayName || ''])))
      setUpdatedAt(new Date())
    } catch (failure) { handleError(failure) } finally { setLoading(false) }
  }, [handleError])
  useEffect(() => { const handler = () => { setPage(pageFromHash()); setMobileMenu(false); setAdminPassword({}); setCustomToken({}); setIssuedToken(null) }; window.addEventListener('hashchange', handler); return () => window.removeEventListener('hashchange', handler) }, [])
  useEffect(() => { let live = true; void fetchSession().then(s => { if (live) setSignedIn(s.username) }).catch(e => { if (live && !(e instanceof ApiError && e.status === 401)) setAuthError(message(e)) }).finally(() => { if (live) setAuthReady(true) }); return () => { live = false } }, [])
  useEffect(() => { if (signedIn) void loadFleet() }, [signedIn, loadFleet])
  useEffect(() => {
    if (!signedIn || !selectedId) { setVehicleStatus(null); return }
    const controller = new AbortController()
    setVehicleStatus(null)
    setStatusError('')
    const refresh = () => { void fetchVehicleStatus(selectedId, controller.signal).then(status => { if (!controller.signal.aborted) { setVehicleStatus(status); setStatusError('') } }).catch(failure => { if (!controller.signal.aborted) { if (failure instanceof ApiError && (failure.status === 401 || failure.status === 403)) clearSession(); else setStatusError(message(failure)) } }) }
    refresh()
    const timer = window.setInterval(refresh, 30_000)
    return () => { window.clearInterval(timer); controller.abort() }
  }, [signedIn, selectedId, clearSession])
  useEffect(() => { setTrips([]); setTripId(''); setGps([]); setTripEvents([]); setCatalog([]); setMetrics([]); setSelectedPoint(null); setSelectedStopEvent(null) }, [signedIn, selectedId])
  useEffect(() => {
    if (!signedIn || !selectedId) return
    const controller = new AbortController()
    setDetailLoading(true)
    setDetailError('')
    void fetchTrips(selectedId, controller.signal).then(rows => {
      if (controller.signal.aborted) return
      const ordered = [...rows].sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt))
      setTrips(ordered)
      setTripId(current => ordered.some(t => t.id === current) ? current : ordered[0]?.id || '')
    }).catch(e => { if (!controller.signal.aborted) setDetailError(message(e)) }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false) })
    return () => controller.abort()
  }, [signedIn, selectedId, refreshTick])
  useEffect(() => { setSelectedPoint(null); setHoverPoint(null); setSelectedStopEvent(null); setHoverStopEvent(null); setNearby([]); setGps([]); setTripEvents([]); setCatalog([]); setMetrics([]) }, [tripId])
  useEffect(() => {
    if (!tripId) return
    const controller = new AbortController()
    setDetailLoading(true)
    setDetailError('')
    void Promise.all([fetchTripGps(tripId, controller.signal), fetchTripMetricCatalog(tripId, controller.signal), fetchTripEvents(tripId, controller.signal)]).then(([points, definitions, events]) => {
      if (controller.signal.aborted) return
      setGps(points)
      setTripEvents(events)
      setCatalog(definitions)
      setMetricName(current => definitions.some(m => m.name === current) ? current : definitions[0]?.name || '')
    }).catch(e => { if (!controller.signal.aborted) setDetailError(message(e)) }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false) })
    return () => controller.abort()
  }, [tripId, refreshTick])
  useEffect(() => { if (!tripId || !metricName || !selectedTrip) { setMetrics([]); return }; const controller = new AbortController(); void fetchTripMetricSamples(tripId, metricName, selectedTrip.startedAt, selectedTrip.endedAt || new Date().toISOString(), controller.signal).then(setMetrics).catch(e => { if (!controller.signal.aborted) setDetailError(message(e)) }); return () => controller.abort() }, [tripId, metricName, selectedTrip, refreshTick])
  useEffect(() => { if (page !== 'explore' || !signedIn) return; const timer = window.setInterval(() => setRefreshTick(value => value + 1), 30_000); return () => window.clearInterval(timer) }, [page, signedIn])
  useEffect(() => { if (!tripId || !displayPoint) { setNearby([]); return }; const controller = new AbortController(); const point = displayPoint; setNearbyLoading(true); const timer = window.setTimeout(() => { void fetchTripTelemetryAt(tripId, point.observedAt, 2000, controller.signal).then(rows => { if (!controller.signal.aborted) setNearby(rows) }).catch(() => { if (!controller.signal.aborted) setNearby([]) }).finally(() => { if (!controller.signal.aborted) setNearbyLoading(false) }) }, 200); return () => { clearTimeout(timer); controller.abort() } }, [tripId, displayPoint?.sampleId])
  useEffect(() => { if (selectedId) localStorage.setItem('opnord.vehicle', selectedId) }, [selectedId])
  useEffect(() => { document.documentElement.dataset.theme = theme; document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#17211d' : '#f5f6f3'); try { localStorage.setItem('opnord.theme', theme) } catch { /* Theme still works for this tab. */ } }, [theme])
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer) }, [])
  async function submitLogin(event: FormEvent) { event.preventDefault(); setAuthBusy(true); setAuthError(''); try { const session = await login(username.trim(), password); setSignedIn(session.username); setPassword('') } catch (e) { setAuthError(e instanceof ApiError && [401, 403].includes(e.status || 0) ? 'Credenziali non valide.' : message(e)) } finally { setPassword(''); setAuthBusy(false) } }
  async function signOut() { try { await logout() } catch { /* Session may already be gone. */ } clearSession() }
  async function saveVehicle(event: FormEvent) { event.preventDefault(); setVehicleBusy(true); setVehicleMessage(''); const input = { displayName: vehicleForm.displayName.trim(), make: vehicleForm.make.trim() || null, model: vehicleForm.model.trim() || null, modelYear: vehicleForm.modelYear ? Number(vehicleForm.modelYear) : null }; try { const vehicle = vehicleEditing === 'new' ? await createVehicle(input) : await updateVehicle(vehicleEditing || '', input); await loadFleet(); setSelectedId(vehicle.id); setVehicleEditing(null); setVehicleMessage(vehicleEditing === 'new' ? 'Mezzo creato.' : 'Mezzo aggiornato.') } catch (e) { setVehicleMessage(message(e)) } finally { setVehicleBusy(false) } }
  function editVehicle(vehicle?: Vehicle) { setVehicleEditing(vehicle?.id || 'new'); setVehicleForm({ displayName: vehicle?.displayName || '', make: vehicle?.make || '', model: vehicle?.model || '', modelYear: vehicle?.modelYear?.toString() || '' }) }
  function selectVehicle(id: string) { setSelectedId(id); setAdminPassword({}); setIssuedToken(null) }
  function openTrip(id: string) { setTripId(id); location.hash = 'explore' }
  async function refreshAll() { await loadFleet(); setRefreshTick(value => value + 1) }
  async function approve(row: PendingDevice) { const secret = adminPassword[row.deviceId] || ''; const vehicleId = adminVehicle[row.deviceId] || ''; const displayName = adminName[row.deviceId]?.trim() || ''; if (!secret || !vehicleId || !displayName) { setAdminMessage('Inserisci nome, mezzo e password.'); return }; setAdminBusy(row.deviceId); setAdminMessage(''); setAdminPassword({}); try { await approvePendingDevice(row.deviceId, secret, vehicleId, displayName); setAdminMessage(`Dispositivo ${row.deviceId} approvato.`); await loadFleet() } catch (e) { setAdminMessage(message(e)) } finally { setAdminBusy('') } }
  async function rotate(device: ManagedDevice) { const secret = adminPassword[device.id] || ''; const token = tokenMode[device.id] === 'custom' ? customToken[device.id] : undefined; if (!secret) { setAdminMessage('Inserisci password account.'); return }; if (tokenMode[device.id] === 'custom' && !/^[A-Za-z0-9_-]{16,128}$/.test(token || '')) { setAdminMessage('Token: 16–128 caratteri, lettere, numeri, _ o -.'); return }; setAdminBusy(device.id); setAdminMessage(''); setAdminPassword({}); setCustomToken({}); setIssuedToken(null); try { const result = await rotateDeviceToken(device.id, secret, token); setIssuedToken({ deviceId: device.id, token: result.deviceToken }); setAdminMessage('Token precedente revocato. Copia il nuovo token e impostalo sul telefono.') } catch (e) { setAdminMessage(message(e)) } finally { setAdminBusy('') } }
  const nav = (p: Page) => <a key={p} href={`#${p}`} className={`nav-link ${page === p ? 'is-active' : ''}`} aria-current={page === p ? 'page' : undefined}>
<span className="nav-icon" aria-hidden="true">
{glyphs[p]}
</span>
<span>
{labels[p]}
</span>
{p === 'admin' && pending.length > 0 && <span className="nav-count">
{pending.length}
</span>}
</a>
  return <div className="app-shell">
    <aside className={`sidebar ${mobileMenu ? 'is-open' : ''}`}>
<a href="#overview" className="brand">
<span className="brand-symbol">O<span>·</span>
</span>
<span>
<strong>OPNORD</strong>
<small>BLACKBOX</small>
</span>
</a>
<div className="nav-caption">WORKSPACE</div>
<nav aria-label="Navigazione principale">
{pages.slice(0, 4).map(nav)}
</nav>
<div className="nav-caption nav-caption-lower">SISTEMA</div>
<nav aria-label="Gestione">
{pages.slice(4).map(nav)}
</nav>
<div className="sidebar-bottom">
<span className="live-dot" /> TELEMETRIA PRIVATA</div>
</aside>
    <main className="main-area">
<header className="topbar">
<button className="menu-toggle" onClick={() => setMobileMenu(v => !v)} aria-label="Apri menu">☰</button>
<span className="breadcrumb">Workspace <span>/</span> <strong>
{labels[page]}
</strong>
</span>
<div className="topbar-actions">
{signedIn && vehicles.length > 0 && <select className="global-vehicle" aria-label="Mezzo selezionato" value={selectedId} onChange={e => selectVehicle(e.target.value)}>
{vehicles.map(v => <option key={v.id} value={v.id}>
{v.displayName}
</option>)}
</select>}{signedIn && <span className="account-name">
{signedIn}
</span>}{signedIn && <button className="quiet-button" onClick={() => void signOut()}>Esci</button>}
</div>
</header>
      <div className="page-content">
{!authReady ? <div className="center-state">Controllo sessione…</div> : !signedIn ? <section className="login-layout">
<div className="login-graphic">
<div className="graphic-grid" />
<div className="graphic-orbit">O</div>
<p>Ogni viaggio, un dato.<br />Ogni dato, una scelta.</p>
</div>
<form className="login-panel" onSubmit={submitLogin}>
<span className="eyebrow">ACCESSO RISERVATO</span>
<h1>La tua flotta.<br />Sotto controllo.</h1>
<p>Accedi ai viaggi, alla posizione e ai segnali del tuo mezzo.</p>
<label>Username<input autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} required />
</label>
<label>Password<input autoComplete="current-password" type="password" value={password} onChange={e => setPassword(e.target.value)} required />
</label>
{authError && <div className="inline-error">
{authError}
</div>}
<button className="primary-button" disabled={authBusy}>
{authBusy ? 'Accesso…' : 'Accedi →'}
</button>
</form>
</section> : <>
        {error && <div className="banner error" role="alert">
{error}
<button onClick={() => void loadFleet()}>Riprova</button>
</div>}
        {page === 'overview' && <Overview selectedVehicle={selectedVehicle} trips={trips} totalDistance={totalDistance} detailLoading={detailLoading} deviceFresh={deviceFresh} updatedAt={updatedAt} loading={loading} loadFleet={refreshAll} openTrip={openTrip} />}
        {page === 'journeys' && <Journeys selectedVehicle={selectedVehicle} trips={trips} detailLoading={detailLoading} openTrip={openTrip} />}
        {page === 'explore' && <Explore trips={trips} tripId={tripId} setTripId={setTripId} selectedTrip={selectedTrip} detailError={detailError} detailLoading={detailLoading} onRefresh={() => setRefreshTick(value => value + 1)} gps={gps} events={tripEvents} displayPoint={displayPoint} setHoverPoint={setHoverPoint} setSelectedPoint={setSelectedPoint} displayStopEvent={displayStopEvent} setHoverStopEvent={setHoverStopEvent} setSelectedStopEvent={setSelectedStopEvent} nearby={nearby} nearbyLoading={nearbyLoading} catalog={catalog} metricName={metricName} setMetricName={setMetricName} metrics={metrics} selectedMetric={selectedMetric} />}
        {page === 'fleet' && <Fleet vehicles={vehicles} devices={devices} selectedId={selectedId} selectedVehicle={selectedVehicle} selectedDevice={selectedDevice} deviceFresh={deviceFresh} vehicleStatus={vehicleStatus} statusError={statusError} vehicleMessage={vehicleMessage} vehicleEditing={vehicleEditing} vehicleForm={vehicleForm} setVehicleForm={setVehicleForm} vehicleBusy={vehicleBusy} saveVehicle={saveVehicle} editVehicle={editVehicle} setVehicleEditing={setVehicleEditing} selectVehicle={selectVehicle} openTrip={openTrip} />}
        {page === 'admin' && <Admin vehicles={vehicles} devices={devices} pending={pending} adminMessage={adminMessage} adminVehicle={adminVehicle} setAdminVehicle={setAdminVehicle} adminName={adminName} setAdminName={setAdminName} adminPassword={adminPassword} setAdminPassword={setAdminPassword} adminBusy={adminBusy} tokenMode={tokenMode} setTokenMode={setTokenMode} customToken={customToken} setCustomToken={setCustomToken} issuedToken={issuedToken} approve={approve} rotate={rotate} loadFleet={loadFleet} />}
        {page === 'settings' && <Settings theme={theme} setTheme={setTheme} signedIn={signedIn} updatedAt={updatedAt} />}
      </>
}
</div>
</main>
{mobileMenu && <button className="menu-scrim" aria-label="Chiudi menu" onClick={() => setMobileMenu(false)} />}
</div>
}

import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, approvePendingDevice, fetchDevices, fetchPendingDevices, fetchSession, fetchTripGps, fetchTripMetricCatalog, fetchTripMetricSamples, fetchTrips, fetchVehicles, GpsSample, login, logout, ManagedDevice, MetricDefinition, MetricSample, PendingDevice, rotateDeviceToken, Trip, Vehicle } from './api'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const shortDateFormat = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' })

function formatDate(value?: string | null) {
  if (!value) return 'In progress'
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? 'Time unavailable' : dateFormat.format(date)
}

function formatDistance(trip: Trip) {
  const meters = trip.distanceGpsM ?? trip.distanceObdM
  if (meters == null || !Number.isFinite(meters)) return '—'
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${Math.round(meters)} m`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Something went wrong while loading the dashboard.'
}

const pidNames: Record<string, string> = {
  '0104': 'Engine load', '0105': 'Coolant temperature', '0106': 'Short-term fuel trim', '0107': 'Long-term fuel trim',
  '010b': 'Intake manifold pressure', '010c': 'Engine speed', '010d': 'Vehicle speed', '010e': 'Ignition timing',
  '010f': 'Intake air temperature', '0110': 'Mass air flow', '0111': 'Throttle position', '011f': 'Run time',
  '012f': 'Fuel level', '0133': 'Barometric pressure', '0142': 'Control module voltage', '0143': 'Absolute engine load',
  '0144': 'Commanded equivalence ratio', '0145': 'Relative throttle', '0146': 'Ambient temperature', '0149': 'Accelerator position',
  '014a': 'Accelerator pedal E', '014b': 'Accelerator pedal F', '014c': 'Throttle actuator', '015e': 'Fuel rate',
  '0161': 'Demanded torque', '0162': 'Actual torque', '0163': 'Reference torque',
}

function metricLabel(metric: MetricDefinition) {
  if (metric.name.startsWith('obd.calc.') && metric.description) return metric.description
  const match = metric.name.match(/^obd\.pid\.([0-9a-f]{4})$/i)
  if (match) return pidNames[match[1].toLowerCase()] || `OBD PID ${match[1].toUpperCase()}`
  const parts = metric.name.split('.')
  const tail = parts[parts.length - 1] || metric.name
  return tail.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function metricGroup(name: string) {
  const source = name.split('.')[0] || 'other'
  return source === 'obd' ? 'OBD sensors' : source === 'gps' ? 'GPS sensors' : source === 'device' ? 'Device sensors' : `${source} sensors`
}

function MetricChart({ samples, unit }: { samples: MetricSample[]; unit: string }) {
  const values = samples.map((sample) => sample.value).filter(Number.isFinite)
  if (!values.length) return <div className="telemetry-empty">No readings recorded for this sensor in the selected journey.</div>
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1
  const points = values.map((value, index) => `${12 + index / Math.max(1, values.length - 1) * 576},${92 - ((value - min) / span) * 72}`).join(' ')
  const latest = values[values.length - 1]
  const lastPoint = points.split(' ')[values.length - 1].split(',')
  return <>
    <div className="telemetry-chart"><svg viewBox="0 0 600 104" role="img" aria-label={`Sensor history, ${values.length} readings`}><path className="telemetry-area" d={`M ${points.replaceAll(' ', ' L ')} L 588,98 L 12,98 Z`} /><polyline points={points} /><circle cx={lastPoint[0]} cy={lastPoint[1]} r="3.5" /></svg></div>
    <div className="telemetry-stats"><span><small>Last loaded</small><strong>{latest.toLocaleString(undefined, { maximumFractionDigits: 2 })} <i>{unit}</i></strong></span><span><small>Loaded range</small><strong>{min.toLocaleString(undefined, { maximumFractionDigits: 2 })}–{max.toLocaleString(undefined, { maximumFractionDigits: 2 })} <i>{unit}</i></strong></span><span><small>Readings loaded</small><strong>{values.length}</strong></span></div>
  </>
}

function RouteSketch({ samples }: { samples: GpsSample[] }) {
  if (samples.length < 2) return <div className="route-placeholder">Not enough GPS fixes to draw a route.</div>
  const latitudes = samples.map((sample) => sample.latitude)
  const longitudes = samples.map((sample) => sample.longitude)
  const minLat = Math.min(...latitudes), maxLat = Math.max(...latitudes)
  const minLon = Math.min(...longitudes), maxLon = Math.max(...longitudes)
  const latSpan = maxLat - minLat || 0.00001, lonSpan = maxLon - minLon || 0.00001
  const points = samples.map((sample) => {
    const x = 24 + ((sample.longitude - minLon) / lonSpan) * 352
    const y = 112 - ((sample.latitude - minLat) / latSpan) * 76
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
  const first = points.split(' ')[0].split(',')
  const pointList = points.split(' ')
  const last = pointList[pointList.length - 1].split(',')
  return <div className="route-sketch">
    <svg viewBox="0 0 400 136" role="img" aria-label={`GPS trace with ${samples.length} location fixes`}>
      <path className="route-line" d={`M ${points.replaceAll(' ', ' L ')}`} />
      <circle className="route-start" cx={first[0]} cy={first[1]} r="4" />
      <circle className="route-end" cx={last[0]} cy={last[1]} r="4" />
    </svg>
    <div className="route-legend"><span><i className="legend-start" />Start</span><span><i className="legend-end" />Latest fix</span></div>
  </div>
}

export default function App() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [signedInUsername, setSignedInUsername] = useState('')
  const [authReady, setAuthReady] = useState(false)
  const [authBusy, setAuthBusy] = useState(false)
  const [authError, setAuthError] = useState('')
  const [vehicles, setVehicles] = useState<Vehicle[]>([])
  const [devices, setDevices] = useState<ManagedDevice[]>([])
  const [pendingDevices, setPendingDevices] = useState<PendingDevice[]>([])
  const [approvalPasswords, setApprovalPasswords] = useState<Record<string, string>>({})
  const [approvalVehicleIds, setApprovalVehicleIds] = useState<Record<string, string>>({})
  const [approvalNames, setApprovalNames] = useState<Record<string, string>>({})
  const [approvingDeviceId, setApprovingDeviceId] = useState('')
  const [pendingError, setPendingError] = useState('')
  const [pendingNotice, setPendingNotice] = useState('')
  const [rotationPasswords, setRotationPasswords] = useState<Record<string, string>>({})
  const [rotationModes, setRotationModes] = useState<Record<string, 'generated' | 'custom'>>({})
  const [customTokens, setCustomTokens] = useState<Record<string, string>>({})
  const [rotatingDeviceId, setRotatingDeviceId] = useState('')
  const [rotationError, setRotationError] = useState('')
  const [rotationErrorDeviceId, setRotationErrorDeviceId] = useState('')
  const [rotationNotice, setRotationNotice] = useState('')
  const [newDeviceToken, setNewDeviceToken] = useState<{ deviceId: string; deviceToken: string } | null>(null)
  const [copyNotice, setCopyNotice] = useState('')
  const [selectedId, setSelectedId] = useState('')
  const [trips, setTrips] = useState<Trip[]>([])
  const [routeSamples, setRouteSamples] = useState<GpsSample[]>([])
  const [routeError, setRouteError] = useState('')
  const [routeLoading, setRouteLoading] = useState(false)
  const [routeTripId, setRouteTripId] = useState('')
  const [metricCatalog, setMetricCatalog] = useState<MetricDefinition[]>([])
  const [selectedMetricName, setSelectedMetricName] = useState('')
  const [metricSamples, setMetricSamples] = useState<MetricSample[]>([])
  const [metricLoading, setMetricLoading] = useState(false)
  const [metricError, setMetricError] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const selected = useMemo(() => vehicles.find((vehicle) => vehicle.id === selectedId) ?? null, [vehicles, selectedId])
  const totalDistance = trips.reduce((sum, trip) => sum + (trip.distanceGpsM ?? trip.distanceObdM ?? 0), 0)
  const selectedMetric = metricCatalog.find((metric) => metric.name === selectedMetricName) ?? metricCatalog[0]

  const endSession = useCallback(() => {
    setSignedInUsername('')
    setVehicles([])
    setDevices([])
    setPendingDevices([])
    setApprovalPasswords({})
    setApprovalVehicleIds({})
    setApprovalNames({})
    setApprovingDeviceId('')
    setPendingError('')
    setPendingNotice('')
    setRotationPasswords({})
    setRotationModes({})
    setCustomTokens({})
    setRotatingDeviceId('')
    setNewDeviceToken(null)
    setRotationError('')
    setRotationErrorDeviceId('')
    setRotationNotice('')
    setCopyNotice('')
    setTrips([])
    setRouteSamples([])
    setRouteTripId('')
    setMetricCatalog([])
    setMetricSamples([])
    setSelectedMetricName('')
    setError('')
  }, [])

  const load = useCallback(async (vehicleId?: string) => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    try {
      const vehicleRows = await fetchVehicles(controller.signal)
      const deviceRows = await fetchDevices(controller.signal)
      const pendingRows = await fetchPendingDevices(controller.signal)
      setVehicles(vehicleRows)
      setDevices(deviceRows)
      setPendingDevices(pendingRows)
      setApprovalVehicleIds((current) => Object.fromEntries(pendingRows.map((row) => [row.deviceId, current[row.deviceId] || row.vehicleId || vehicleRows[0]?.id || ''])))
      setApprovalNames((current) => Object.fromEntries(pendingRows.map((row) => [row.deviceId, current[row.deviceId] || row.displayName || ''])))
      const nextId = vehicleId && vehicleRows.some((vehicle) => vehicle.id === vehicleId) ? vehicleId : vehicleRows[0]?.id || ''
      setSelectedId(nextId)
      if (!nextId) {
        setTrips([])
        setRouteSamples([])
        setRouteTripId('')
        setRouteError('')
        setLastUpdated(new Date())
        return
      }
      const tripRows = await fetchTrips(nextId, controller.signal)
      const orderedTrips = [...tripRows].sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt))
      setTrips(orderedTrips)
      if (orderedTrips[0]) {
        setRouteTripId(orderedTrips[0].id)
        setRouteLoading(true)
        try {
          setRouteSamples(await fetchTripGps(orderedTrips[0].id, controller.signal))
          setRouteError('')
        } catch (routeLoadError) {
          if (routeLoadError instanceof ApiError && routeLoadError.status === 401) {
            endSession()
            return
          }
          setRouteSamples([])
          setRouteError(routeLoadError instanceof ApiError && routeLoadError.status === 404 ? 'GPS route endpoint is not available yet.' : errorMessage(routeLoadError))
        } finally {
          setRouteLoading(false)
        }
      } else {
        setRouteSamples([])
        setRouteTripId('')
        setRouteError('')
      }
      setLastUpdated(new Date())
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 401) {
        endSession()
        return
      }
      setError(errorMessage(loadError))
      setVehicles([])
      setTrips([])
      setRouteSamples([])
      setRouteTripId('')
    } finally {
      setLoading(false)
    }
  }, [endSession])

  useEffect(() => {
    let active = true
    void fetchSession().then((session) => {
      if (active) setSignedInUsername(session.username)
    }).catch((sessionError) => {
      if (active && !(sessionError instanceof ApiError && sessionError.status === 401)) setAuthError(errorMessage(sessionError))
    }).finally(() => {
      if (active) setAuthReady(true)
    })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (signedInUsername) void load()
  }, [signedInUsername, load])

  useEffect(() => {
    if (!signedInUsername || !routeTripId) {
      setMetricCatalog([]); setMetricSamples([]); setSelectedMetricName(''); setMetricError('')
      return
    }
    const controller = new AbortController()
    setMetricLoading(true); setMetricError(''); setMetricSamples([])
    void fetchTripMetricCatalog(routeTripId, controller.signal).then((catalog) => {
      if (controller.signal.aborted) return
      const ordered = [...catalog].sort((a, b) => metricGroup(a.name).localeCompare(metricGroup(b.name)) || metricLabel(a).localeCompare(metricLabel(b)))
      setMetricCatalog(ordered)
      setSelectedMetricName((current) => ordered.some((metric) => metric.name === current) ? current : ordered[0]?.name || '')
    }).catch((catalogError) => {
      if (controller.signal.aborted) return
      if (catalogError instanceof ApiError && catalogError.status === 401) { endSession(); return }
      setMetricCatalog([]); setMetricError(catalogError instanceof ApiError && catalogError.status === 404 ? 'Sensor catalog endpoint is not available yet.' : errorMessage(catalogError))
    }).finally(() => { if (!controller.signal.aborted) setMetricLoading(false) })
    return () => controller.abort()
  }, [signedInUsername, routeTripId, endSession])

  useEffect(() => {
    if (!routeTripId || !selectedMetricName) { setMetricSamples([]); return }
    const trip = trips.find((row) => row.id === routeTripId)
    if (!trip?.startedAt) return
    const controller = new AbortController()
    const end = trip.endedAt || new Date().toISOString()
    setMetricLoading(true); setMetricError('')
    void fetchTripMetricSamples(routeTripId, selectedMetricName, trip.startedAt, end, controller.signal).then(setMetricSamples).catch((samplesError) => {
      if (controller.signal.aborted) return
      if (samplesError instanceof ApiError && samplesError.status === 401) { endSession(); return }
      setMetricSamples([]); setMetricError(errorMessage(samplesError))
    }).finally(() => { if (!controller.signal.aborted) setMetricLoading(false) })
    return () => controller.abort()
  }, [routeTripId, selectedMetricName, trips, endSession])

  async function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const cleanUsername = username.trim()
    if (!cleanUsername || !password) {
      setAuthError('Enter your username and password.')
      return
    }
    setAuthBusy(true)
    setAuthError('')
    try {
      const session = await login(cleanUsername, password)
      setSignedInUsername(session.username)
      setPassword('')
      setError('')
    } catch (loginError) {
      setAuthError(loginError instanceof ApiError && (loginError.status === 401 || loginError.status === 403) ? 'Incorrect username or password.' : errorMessage(loginError))
    } finally {
      setPassword('')
      setAuthBusy(false)
    }
  }

  async function disconnect() {
    try { await logout() } catch { /* Clear local view even if the session already expired. */ }
    endSession()
  }

  async function refreshPending() {
    setPendingError(''); setPendingNotice('')
    setApprovalPasswords({})
    try { setPendingDevices(await fetchPendingDevices()) }
    catch (failure) { if (failure instanceof ApiError && failure.status === 401) { endSession(); return }; setPendingError(errorMessage(failure)) }
  }

  async function approvePending(row: PendingDevice) {
    const password = approvalPasswords[row.deviceId] || ''
    const vehicleId = approvalVehicleIds[row.deviceId] || ''
    const displayName = approvalNames[row.deviceId]?.trim() || ''
    if (!row.approvable) { setPendingError('This request cannot be approved. Check its reason and token format.'); return }
    if (!password || !vehicleId || !displayName) { setPendingError('Choose a vehicle, enter a device name and confirm your account password.'); return }
    setApprovalPasswords((current) => ({ ...current, [row.deviceId]: '' }))
    setApprovingDeviceId(row.deviceId); setPendingError(''); setPendingNotice('')
    try {
      await approvePendingDevice(row.deviceId, password, vehicleId, displayName)
      setPendingNotice(`${displayName} approved. Its observed token hash is now trusted.`)
      await load(selectedId)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) { endSession(); return }
      setPendingError(failure instanceof ApiError && failure.status === 403 ? 'Incorrect account password.' : failure instanceof ApiError && failure.status === 409 ? 'This failure is no longer eligible for approval. For token mismatch, use the existing device rotation form.' : errorMessage(failure))
    } finally { setApprovingDeviceId('') }
  }

  function chooseVehicle(id: string) {
    setRotationPasswords({}); setRotationModes({}); setCustomTokens({}); setNewDeviceToken(null); setRotationError(''); setRotationErrorDeviceId(''); setRotationNotice(''); setCopyNotice('')
    setApprovalPasswords({})
    setSelectedId(id)
    if (signedInUsername) void load(id)
  }

  async function handleRotateToken(device: ManagedDevice) {
    const suppliedPassword = rotationPasswords[device.id] || ''
    if (!suppliedPassword) { setRotationErrorDeviceId(device.id); setRotationError('Enter your account password to rotate this device token.'); return }
    const mode = rotationModes[device.id] || 'generated'
    const suppliedToken = customTokens[device.id] || ''
    if (mode === 'custom' && !/^[A-Za-z0-9_-]{16,128}$/.test(suppliedToken)) {
      setRotationErrorDeviceId(device.id); setRotationError('Custom tokens must be 16–128 characters using only ASCII letters, numbers, underscores, or hyphens. Spaces are not allowed.'); return
    }
    setRotationPasswords({})
    setCustomTokens((current) => ({ ...current, [device.id]: '' }))
    setRotatingDeviceId(device.id); setRotationError(''); setRotationErrorDeviceId(''); setRotationNotice(''); setNewDeviceToken(null); setCopyNotice('')
    try {
      const result = await rotateDeviceToken(device.id, suppliedPassword, mode === 'custom' ? suppliedToken : undefined)
      setNewDeviceToken(result)
      setRotationNotice(`The previous token is now invalid. Enter this new token in the phone app to reconnect it.${result.credentialsFileUpdated === false ? ' The server credentials JSON was not updated; this token is active, so save it now.' : ''}`)
    } catch (rotationFailure) {
      if (rotationFailure instanceof ApiError && rotationFailure.status === 401) { endSession(); return }
      setRotationErrorDeviceId(device.id)
      setRotationError(rotationFailure instanceof ApiError && rotationFailure.status === 403 ? 'Incorrect password.' : rotationFailure instanceof ApiError && rotationFailure.status === 409 ? 'Choose a token different from the current one.' : errorMessage(rotationFailure))
    } finally { setRotatingDeviceId('') }
  }

  async function copyNewToken() {
    if (!newDeviceToken) return
    try { await navigator.clipboard.writeText(newDeviceToken.deviceToken); setCopyNotice('Copied') }
    catch { setCopyNotice('Copy unavailable. Select and copy the token.') }
  }

  useEffect(() => {
    const clearSecrets = () => { setRotationPasswords({}); setCustomTokens({}); setRotationModes({}); setApprovalPasswords({}); setNewDeviceToken(null); setRotationError(''); setRotationErrorDeviceId(''); setRotationNotice(''); setCopyNotice('') }
    window.addEventListener('hashchange', clearSecrets)
    window.addEventListener('pagehide', clearSecrets)
    return () => { window.removeEventListener('hashchange', clearSecrets); window.removeEventListener('pagehide', clearSecrets) }
  }, [])

  async function showRoute(trip: Trip) {
    if (!signedInUsername) return
    setRouteTripId(trip.id)
    setRouteLoading(true)
    setRouteError('')
    try {
      setRouteSamples(await fetchTripGps(trip.id))
    } catch (routeLoadError) {
      if (routeLoadError instanceof ApiError && routeLoadError.status === 401) {
        endSession()
        return
      }
      setRouteSamples([])
      setRouteError(routeLoadError instanceof ApiError && routeLoadError.status === 404 ? 'GPS route endpoint is not available yet.' : errorMessage(routeLoadError))
    } finally {
      setRouteLoading(false)
    }
  }

  const statusText = loading ? 'Updating' : error ? 'Needs attention' : signedInUsername ? 'Connected' : 'Not connected'

  return (
    <div className="app-shell">
      <aside className="rail" aria-label="Main navigation">
        <a className="brand" href="#overview" aria-label="Opnord overview"><span className="brand-mark"><i /><i /><i /></span><span>opnord</span></a>
        <div className="rail-rule" />
        <a className="rail-link active" href="#overview" aria-current="page" title="Overview"><span className="rail-icon gauge-icon" aria-hidden="true"><i /></span><span className="rail-label">Overview</span></a>
        <div className="rail-bottom"><span className="rail-caption">PRIVATE<br />FLEET</span><span className="rail-dot" /></div>
      </aside>

      <main id="overview" className="main-area">
        <header className="topbar">
          <div className="breadcrumbs"><span>Workspace</span><span className="crumb-slash">/</span><strong>Overview</strong></div>
          {signedInUsername && <div className="session-controls"><span>{signedInUsername}</span><button className="connect-button" type="button" onClick={() => void disconnect()}>Sign out</button></div>}
        </header>

        <div className="page-content">
          <section className="page-intro">
            <div><p className="eyebrow">Vehicle telemetry</p><h1>Good morning.</h1><p className="intro-copy">A clear view of your vehicles, journeys and blackbox health.</p></div>
            <div className={`connection-state ${error ? 'is-error' : ''}`} aria-live="polite"><span className="state-indicator" /><span>{statusText}</span>{lastUpdated && !loading && <small>Updated {new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(lastUpdated)}</small>}</div>
          </section>

          {error && <section className="alert" role="alert"><span className="alert-mark">!</span><div><strong>Dashboard could not load</strong><p>{error}</p></div>{signedInUsername && <button className="text-button" onClick={() => void load(selectedId)}>Try again</button>}</section>}

          {!authReady && <section className="welcome-panel"><div className="loading-state"><span className="spinner" />Checking your session…</div></section>}

          {authReady && !signedInUsername && <section className="welcome-panel">
            <div className="welcome-art" aria-hidden="true"><div className="signal signal-one" /><div className="signal signal-two" /><div className="signal signal-three" /><div className="signal-core"><span>O</span></div></div>
            <form className="welcome-copy login-form" onSubmit={connect}>
              <p className="eyebrow">Your data stays yours</p><h2>Sign in to your<br />vehicle workspace.</h2><p>Use your account credentials to view vehicles and recent journeys.</p>
              <label htmlFor="username">Username</label><input id="username" name="username" autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} required />
              <label htmlFor="password">Password</label><input id="password" name="password" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
              {authError && <p className="login-error" role="alert">{authError}</p>}
              <button className="primary-button" type="submit" disabled={authBusy}>{authBusy ? 'Signing in…' : 'Sign in'}</button>
            </form>
            <div className="welcome-foot"><span>LIVE TELEMETRY</span><span>PRIVATE BY DESIGN</span></div>
          </section>}

          {signedInUsername && !error && <>
            <section className="metrics-grid" aria-label="Fleet summary">
              <article className="metric metric-vehicle">
                <div className="metric-top"><span className="metric-label">Selected vehicle</span><span className="metric-icon car-icon" aria-hidden="true">↗</span></div>
                {vehicles.length > 0 ? <>
                  <select aria-label="Choose vehicle" value={selectedId} onChange={(event) => chooseVehicle(event.target.value)} disabled={loading}>{vehicles.map((vehicle) => <option key={vehicle.id} value={vehicle.id}>{vehicle.displayName}</option>)}</select>
                  <div className="metric-foot">{selected ? [selected.modelYear, selected.make, selected.model].filter(Boolean).join(' ') || 'Vehicle details not set' : 'Loading vehicle'}</div>
                </> : <><div className="metric-value">{loading ? 'Loading…' : 'No vehicles'}</div><div className="metric-foot">Add a vehicle to begin</div></>}
              </article>
              <article className="metric"><div className="metric-top"><span className="metric-label">Recent journeys</span><span className="metric-icon route-icon" aria-hidden="true">⌁</span></div><div className="metric-value">{loading ? '—' : trips.length}</div><div className="metric-foot">Last 30 recorded trips</div></article>
              <article className="metric"><div className="metric-top"><span className="metric-label">Recorded distance</span><span className="metric-icon distance-icon" aria-hidden="true">↗</span></div><div className="metric-value">{loading ? '—' : <>{totalDistance >= 1000 ? (totalDistance / 1000).toFixed(1) : Math.round(totalDistance)} <small>{totalDistance >= 1000 ? 'km' : 'm'}</small></>}</div><div className="metric-foot">Across the journeys shown</div></article>
              <article className="metric metric-device"><div className="metric-top"><span className="metric-label">Blackbox</span><span className="device-pip" /></div><div className="metric-value device-value">Unavailable</div><div className="metric-foot">Device status is not exposed by the API yet</div></article>
            </section>

            <section className="devices-section">
              <div className="section-heading"><div><p className="eyebrow">Device access</p><h2>Blackbox devices</h2></div><span className="count-pill">{devices.length} devices</span></div>
              {devices.length === 0 ? <div className="device-list-empty">No devices are registered to this account.</div> : <div className="managed-devices">{devices.map((device) => <article className="managed-device" key={device.id}>
                <div className="managed-device-summary"><span className="managed-device-icon" aria-hidden="true">⌁</span><div><strong>{device.displayName || 'Blackbox device'}</strong><small>{vehicles.find((vehicle) => vehicle.id === device.vehicleId)?.displayName || 'Vehicle'} · {device.id}</small></div><span className={`token-state ${device.tokenRevokedAt ? 'revoked' : ''}`}>{device.tokenRevokedAt ? 'Token revoked' : 'Token active'}</span></div>
                <form className="rotate-form" onSubmit={(event) => { event.preventDefault(); void handleRotateToken(device) }}>
                  <label htmlFor={`rotate-mode-${device.id}`}>Token choice</label><select id={`rotate-mode-${device.id}`} value={rotationModes[device.id] || 'generated'} onChange={(event) => { const value = event.target.value as 'generated' | 'custom'; setRotationModes((current) => ({ ...current, [device.id]: value })); setRotationError(''); setRotationErrorDeviceId('') }}><option value="generated">Generate token</option><option value="custom">Set my own token</option></select>
                  {(rotationModes[device.id] || 'generated') === 'custom' && <><label htmlFor={`custom-token-${device.id}`}>Custom device token</label><input id={`custom-token-${device.id}`} autoComplete="off" value={customTokens[device.id] || ''} onChange={(event) => setCustomTokens((current) => ({ ...current, [device.id]: event.target.value }))} placeholder="16–128 letters, numbers, _ or -" aria-describedby={`token-help-${device.id}`} /><small className="token-help" id={`token-help-${device.id}`}>16–128 ASCII letters, numbers, underscores, or hyphens. No spaces. Choose a long, unpredictable value.</small></>}
                  <label htmlFor={`rotate-password-${device.id}`}>Account password</label><input id={`rotate-password-${device.id}`} type="password" autoComplete="current-password" value={rotationPasswords[device.id] || ''} onChange={(event) => setRotationPasswords((current) => ({ ...current, [device.id]: event.target.value }))} placeholder="Confirm your password" />
                  <button className="rotate-button" type="submit" disabled={Boolean(device.tokenRevokedAt) || rotatingDeviceId === device.id}>{rotatingDeviceId === device.id ? 'Rotating…' : 'Rotate token'}</button>
                </form>
                {rotationError && rotationErrorDeviceId === device.id && <p className="rotation-error" role="alert">{rotationError}</p>}
                {newDeviceToken?.deviceId === device.id && <div className="new-token-panel" role="status"><strong>New device token</strong><p>{rotationNotice}</p><div className="token-copy-row"><input aria-label="New device token" readOnly value={newDeviceToken.deviceToken} onFocus={(event) => event.currentTarget.select()} /><button type="button" onClick={() => void copyNewToken()}>{copyNotice || 'Copy token'}</button></div></div>}
              </article>)}</div>}
            </section>

            <section className="pending-section">
              <div className="section-heading"><div><p className="eyebrow">Connection review</p><h2>Failed device connections</h2></div><div className="pending-heading-actions"><span className="count-pill">{pendingDevices.length} pending</span><button className="refresh-button" type="button" onClick={() => void refreshPending()}>Refresh</button></div></div>
              {pendingError && <p className="pending-error" role="alert">{pendingError}</p>}
              {pendingNotice && <p className="pending-notice" role="status">{pendingNotice}</p>}
              {pendingDevices.length === 0 ? <div className="device-list-empty">No failed device connections recorded.</div> : <div className="pending-list">{pendingDevices.map((row) => <article className="pending-device" key={row.deviceId}>
                <div className="pending-summary"><div><span className="eyebrow">{row.reason.replaceAll('_', ' ')}</span><strong>{row.displayName || 'Unrecognized device'}</strong><small>Last seen {formatDate(row.lastSeenAt)} · {row.attemptCount} attempts</small></div><button type="button" className="copy-id-button" onClick={() => void navigator.clipboard.writeText(row.deviceId)}>Copy ID</button></div>
                <code className="pending-device-id">{row.deviceId}</code><small className="pending-batch">Last batch: {row.lastBatchId || '—'} · First seen {formatDate(row.firstSeenAt)}</small>
                {row.approvable ? <div className="pending-approval">
                  <label htmlFor={`approve-vehicle-${row.deviceId}`}>Assign vehicle</label><select id={`approve-vehicle-${row.deviceId}`} value={approvalVehicleIds[row.deviceId] || ''} onChange={(event) => setApprovalVehicleIds((current) => ({ ...current, [row.deviceId]: event.target.value }))}><option value="">Choose a vehicle</option>{vehicles.map((vehicle) => <option value={vehicle.id} key={vehicle.id}>{vehicle.displayName}</option>)}</select>
                  <label htmlFor={`approve-name-${row.deviceId}`}>Device display name</label><input id={`approve-name-${row.deviceId}`} value={approvalNames[row.deviceId] || ''} onChange={(event) => setApprovalNames((current) => ({ ...current, [row.deviceId]: event.target.value }))} placeholder="e.g. Opnord in Fiat Panda" />
                  <label htmlFor={`approve-password-${row.deviceId}`}>Account password</label><input id={`approve-password-${row.deviceId}`} type="password" autoComplete="current-password" value={approvalPasswords[row.deviceId] || ''} onChange={(event) => setApprovalPasswords((current) => ({ ...current, [row.deviceId]: event.target.value }))} placeholder="Confirm your password" />
                  <p>Verify this device ID against the phone before approval. Approval trusts the token hash from its last rejected request; the token stays on the phone and is never shown here.</p><button type="button" className="rotate-button" disabled={approvingDeviceId === row.deviceId || vehicles.length === 0} onClick={() => void approvePending(row)}>{approvingDeviceId === row.deviceId ? 'Approving…' : 'Approve device'}</button>
                </div> : <p className="pending-guidance">{row.reason === 'unknown_device_id' ? 'Token from the last rejected request is too short or has unsupported characters. Set a 16–128 character token on the phone, then retry sync.' : row.reason === 'token_mismatch' ? 'This registered device has a token mismatch. Use the existing device rotation form above to issue a token and update the phone.' : 'This device was revoked and cannot be approved from this list.'}</p>}
              </article>)}</div>}
            </section>

            <section className="journeys-section">
              <div className="section-heading"><div><p className="eyebrow">On the road</p><h2>Recent journeys</h2></div><span className="count-pill">{trips.length} shown</span></div>
              <div className="journey-table-wrap">
                <table className="journey-table"><thead><tr><th scope="col">Journey</th><th scope="col">Started</th><th scope="col">Duration</th><th scope="col">Distance</th><th scope="col">Status</th></tr></thead>
                  <tbody>{trips.map((trip) => {
                    const start = new Date(trip.startedAt)
                    const end = trip.endedAt ? new Date(trip.endedAt) : null
                    const elapsed = end && !Number.isNaN(start.valueOf()) ? Math.max(0, end.valueOf() - start.valueOf()) : 0
                    const mins = Math.round(elapsed / 60000)
                    return <tr key={trip.id}>
                      <td><span className="route-badge"><span /></span><span className="trip-name">{trip.startReason ? trip.startReason.replaceAll('_', ' ') : 'Recorded journey'}</span></td>
                      <td><span className="date-primary">{formatDate(trip.startedAt)}</span><span className="date-secondary">{!Number.isNaN(start.valueOf()) ? shortDateFormat.format(start) : '—'}</span></td>
                      <td>{end ? `${Math.floor(mins / 60)}h ${mins % 60}m` : 'In progress'}</td><td className="distance-cell">{formatDistance(trip)}</td>
                      <td><button className="route-button" onClick={() => void showRoute(trip)} aria-label={`Show GPS route for journey started ${formatDate(trip.startedAt)}`}>View route</button><span className={`trip-status ${end ? 'complete' : 'active-trip'}`}><i />{end ? 'Complete' : 'In progress'}</span></td>
                    </tr>
                  })}</tbody>
                </table>
                {!loading && trips.length === 0 && <div className="empty-state"><div className="empty-route" aria-hidden="true"><span /><span /><span /></div><h3>No journeys yet</h3><p>Trips will appear here after the blackbox syncs its first drive.</p></div>}
                {loading && <div className="loading-state"><span className="spinner" />Loading vehicle data…</div>}
              </div>
            </section>

            {routeTripId && <section className="telemetry-section" aria-live="polite">
              <div className="section-heading"><div><p className="eyebrow">Vehicle signals</p><h2>Sensor readings</h2></div><span className="count-pill">{metricCatalog.length} metrics</span></div>
              {metricLoading && metricCatalog.length === 0 ? <div className="telemetry-empty"><span className="spinner" />Loading sensor catalog…</div> : metricError && metricCatalog.length === 0 ? <div className="telemetry-empty">{metricError}</div> : metricCatalog.length === 0 ? <div className="telemetry-empty">No sensor readings were recorded for this journey.</div> : <div className="telemetry-layout">
                <div className="sensor-list" aria-label="Available sensors">
                  {[...new Set(metricCatalog.map((metric) => metricGroup(metric.name)))].map((group) => <div className="sensor-group" key={group}><p>{group}</p>{metricCatalog.filter((metric) => metricGroup(metric.name) === group).map((metric) => <button key={metric.name} type="button" className={`sensor-item ${selectedMetricName === metric.name ? 'selected' : ''}`} onClick={() => setSelectedMetricName(metric.name)} aria-pressed={selectedMetricName === metric.name}><span>{metricLabel(metric)}<small>{metric.name}</small></span><b>{metric.unit || '—'}</b></button>)}</div>)}
                </div>
                <article className="sensor-detail">
                  <div className="sensor-detail-heading"><div><p className="eyebrow">{selectedMetric ? metricGroup(selectedMetric.name) : 'Sensor'}</p><h3>{selectedMetric ? metricLabel(selectedMetric) : 'Sensor history'}</h3>{selectedMetric?.description && <p>{selectedMetric.description}</p>}</div><span className="sensor-unit">{selectedMetric?.unit || '—'}</span></div>
                  {metricLoading ? <div className="telemetry-empty"><span className="spinner" />Loading readings…</div> : metricError ? <div className="telemetry-empty">{metricError}</div> : <MetricChart samples={metricSamples} unit={selectedMetric?.unit || ''} />}
                </article>
              </div>}
            </section>}

            {routeTripId && <section className="route-panel" aria-live="polite">
              <div className="route-panel-heading"><div><p className="eyebrow">Journey detail</p><h2>GPS route</h2></div><span>{routeLoading ? 'Loading fixes…' : `${routeSamples.length} GPS fixes`}</span></div>
              {routeLoading ? <div className="route-placeholder"><span className="spinner" />Loading route data…</div> : routeSamples.length > 0 ? <RouteSketch samples={routeSamples} /> : <div className="route-placeholder">{routeError || 'No GPS fixes were recorded for this journey.'}</div>}
              <p className="route-privacy">Route points are shown without map labels or address details.</p>
            </section>}
          </>}

          <footer className="page-footer"><span>Opnord vehicle telemetry</span><span>{import.meta.env.VITE_API_BASE_URL || 'API base: same origin'} <b>·</b> Data refreshed on demand</span></footer>
        </div>
      </main>
    </div>
  )
}

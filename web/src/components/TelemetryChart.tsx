import { useEffect, useMemo, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent, KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { MetricSample } from '../api'
import './TelemetryChart.css'

type Props = {
  samples: MetricSample[]
  unit: string
  selectedAt?: string | null
  onHoverAt?: (iso: string | null) => void
  onSelectAt?: (iso: string) => void
  onZoom?: (from: number, to: number) => void
}

const W = 900
const H = 300
const PAD = { top: 22, right: 24, bottom: 38, left: 64 }
const plotW = W - PAD.left - PAD.right
const plotH = H - PAD.top - PAD.bottom
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' })

function niceStep(span: number, ticks = 5) {
  const raw = span / ticks
  const magnitude = 10 ** Math.floor(Math.log10(raw || 1))
  const factor = raw / magnitude
  return (factor <= 1 ? 1 : factor <= 2 ? 2 : factor <= 5 ? 5 : 10) * magnitude
}

export default function TelemetryChart({ samples, unit, selectedAt, onHoverAt, onSelectAt, onZoom }: Props) {
  const rootRef = useRef<HTMLDivElement>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const dragRef = useRef<{ x: number; current: number } | null>(null)
  const [, setWidth] = useState(600)
  const [hoverIndex, setHoverIndex] = useState<number | null>(null)
  const [brush, setBrush] = useState<[number, number] | null>(null)
  const [drag, setDrag] = useState<[number, number] | null>(null)
  const [focusIndex, setFocusIndex] = useState(0)

  useEffect(() => {
    const el = rootRef.current
    if (!el) return
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(320, entry.contentRect.width)))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const points = useMemo(() => samples
    .filter(s => Number.isFinite(s.value) && Number.isFinite(Date.parse(s.observedAt)))
    .sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt)), [samples])

  const domain = useMemo<[number, number]>(() => {
    if (!points.length) return [0, 1]
    const all: [number, number] = [Date.parse(points[0].observedAt), Date.parse(points[points.length - 1].observedAt)]
    return brush ?? (all[0] === all[1] ? [all[0] - 1000, all[1] + 1000] : all)
  }, [points, brush])

  const visible = useMemo(() => points.filter(p => {
    const t = Date.parse(p.observedAt)
    return t >= domain[0] && t <= domain[1]
  }), [points, domain])

  const { path, yTicks, bounds } = useMemo(() => {
    const values = visible.map(p => p.value)
    let min = Math.min(...values), max = Math.max(...values)
    if (!Number.isFinite(min)) { min = 0; max = 1 }
    if (min === max) { const padding = Math.abs(min) * .08 || 1; min -= padding; max += padding }
    const rawStep = niceStep(max - min)
    const lo = Math.floor(min / rawStep) * rawStep
    const hi = Math.ceil(max / rawStep) * rawStep
    const y = (v: number) => PAD.top + (hi - v) / (hi - lo || 1) * plotH
    const x = (t: number) => PAD.left + (t - domain[0]) / (domain[1] - domain[0] || 1) * plotW
    const d = visible.map((p, i) => `${i ? 'L' : 'M'}${x(Date.parse(p.observedAt)).toFixed(2)},${y(p.value).toFixed(2)}`).join(' ')
    return { path: d, yTicks: Array.from({ length: Math.max(2, Math.round((hi - lo) / rawStep) + 1) }, (_, i) => lo + i * rawStep).filter(v => v <= hi + rawStep / 100), bounds: { x, y, lo, hi } }
  }, [visible, domain])

  const xTicks = useMemo(() => Array.from({ length: 5 }, (_, i) => domain[0] + (domain[1] - domain[0]) * i / 4), [domain])
  const selectedIndex = selectedAt ? visible.reduce((best, p, i) => Math.abs(Date.parse(p.observedAt) - Date.parse(selectedAt)) < Math.abs(Date.parse(visible[best]?.observedAt ?? selectedAt) - Date.parse(selectedAt)) ? i : best, 0) : -1
  const activeIndex = hoverIndex ?? (selectedIndex >= 0 ? selectedIndex : -1)
  const active = activeIndex >= 0 ? visible[activeIndex] : undefined
  const valueFmt = (v: number) => Number(v.toPrecision(6)).toLocaleString()

  function localX(event: ReactPointerEvent<SVGSVGElement>) {
    const rect = svgRef.current!.getBoundingClientRect()
    return Math.max(PAD.left, Math.min(W - PAD.right, (event.clientX - rect.left) / rect.width * W))
  }
  function timeAtX(x: number) { return domain[0] + (x - PAD.left) / plotW * (domain[1] - domain[0]) }
  function nearestIndex(time: number) {
    let low = 0, high = visible.length - 1
    while (low < high) { const mid = (low + high) >>> 1; if (Date.parse(visible[mid].observedAt) < time) low = mid + 1; else high = mid }
    if (low > 0 && Math.abs(Date.parse(visible[low - 1].observedAt) - time) < Math.abs(Date.parse(visible[low].observedAt) - time)) return low - 1
    return low
  }
  function handleMove(event: ReactPointerEvent<SVGSVGElement>) {
    if (!visible.length) return
    const x = localX(event)
    if (dragRef.current) {
      dragRef.current.current = x
      setDrag([Math.min(x, dragRef.current.x), Math.max(x, dragRef.current.x)])
      return
    }
    const index = nearestIndex(timeAtX(x))
    setHoverIndex(index)
    onHoverAt?.(visible[index].observedAt)
  }
  function handleUp() {
    if (!dragRef.current) return
    const { x, current } = dragRef.current
    dragRef.current = null
    setDrag(null)
    if (Math.abs(current - x) > 8) {
      const a = timeAtX(Math.min(x, current)), b = timeAtX(Math.max(x, current))
      setBrush([a, b])
      onZoom?.(a, b)
      setHoverIndex(null)
      onHoverAt?.(null)
    }
  }
  function handleKey(event: ReactKeyboardEvent<SVGSVGElement>) {
    if (!visible.length) return
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const next = Math.max(0, Math.min(visible.length - 1, (hoverIndex ?? (selectedIndex >= 0 ? selectedIndex : focusIndex)) + (event.key === 'ArrowRight' ? 1 : -1)))
      setFocusIndex(next); setHoverIndex(next); onHoverAt?.(visible[next].observedAt); onSelectAt?.(visible[next].observedAt)
    } else if (event.key === 'Enter' && active) onSelectAt?.(active.observedAt)
    else if (event.key === 'Escape') { setHoverIndex(null); onHoverAt?.(null) }
  }

  return <section className="telemetry-chart" ref={rootRef} aria-label="Interactive telemetry chart">
    <div className="telemetry-chart__topline"><span>{points.length.toLocaleString()} campioni</span><span>Trascina per zoom · ← → per esplorare · Invio per selezionare</span>{brush && <button type="button" onClick={() => { setBrush(null); setHoverIndex(null) }}>Ripristina vista</button>}</div>
    {!points.length ? <div className="telemetry-chart__empty">Nessun campione per questo segnale.</div> : !visible.length ? <div className="telemetry-chart__empty">Nessun campione in questo intervallo.</div> : <div className="telemetry-chart__canvas">
      <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} role="application" aria-label={`${points[0].name} over time, ${points.length} campioni`} tabIndex={0} onKeyDown={handleKey} onClick={() => active && onSelectAt?.(active.observedAt)} onPointerMove={handleMove} onPointerDown={e => { if (e.button !== 0) return; const x = localX(e); dragRef.current = { x, current: x }; e.currentTarget.setPointerCapture(e.pointerId) }} onPointerUp={handleUp} onPointerCancel={handleUp} onPointerLeave={() => { if (!dragRef.current) { setHoverIndex(null); onHoverAt?.(null) } }}>
        <defs><clipPath id="telemetry-clip"><rect x={PAD.left} y={PAD.top} width={plotW} height={plotH}/></clipPath></defs>
        {yTicks.map(t => <g key={t}><line className="telemetry-chart__grid" x1={PAD.left} x2={W - PAD.right} y1={bounds.y(t)} y2={bounds.y(t)}/><text className="telemetry-chart__axis" x={PAD.left - 10} y={bounds.y(t) + 4} textAnchor="end">{valueFmt(t)}</text></g>)}
        {xTicks.map((t, i) => <g key={i}><line className="telemetry-chart__grid telemetry-chart__grid--vertical" x1={PAD.left + plotW * i / 4} x2={PAD.left + plotW * i / 4} y1={PAD.top} y2={H - PAD.bottom}/><text className="telemetry-chart__axis" x={PAD.left + plotW * i / 4} y={H - 12} textAnchor={i === 0 ? 'start' : i === 4 ? 'end' : 'middle'}>{timeFmt.format(t)}</text></g>)}
        <g clipPath="url(#telemetry-clip)"><path className="telemetry-chart__line" d={path}/>
          {selectedIndex >= 0 && <line className="telemetry-chart__selected" x1={bounds.x(Date.parse(visible[selectedIndex].observedAt))} x2={bounds.x(Date.parse(visible[selectedIndex].observedAt))} y1={PAD.top} y2={H - PAD.bottom}/>}
          {active && <><line className="telemetry-chart__crosshair" x1={bounds.x(Date.parse(active.observedAt))} x2={bounds.x(Date.parse(active.observedAt))} y1={PAD.top} y2={H - PAD.bottom}/><circle className="telemetry-chart__dot" cx={bounds.x(Date.parse(active.observedAt))} cy={bounds.y(active.value)} r="5"/></>}
          {drag && <rect className="telemetry-chart__brush" x={drag[0]} y={PAD.top} width={drag[1] - drag[0]} height={plotH}/>}
        </g>
      </svg>
      {active && <div className="telemetry-chart__tooltip" style={{ left: `${Math.min(88, Math.max(2, bounds.x(Date.parse(active.observedAt)) / W * 100))}%`, top: `${Math.max(5, bounds.y(active.value) / H * 100)}%` }}><strong>{valueFmt(active.value)} {unit}</strong><span>{dateFmt.format(Date.parse(active.observedAt))}</span><span className="telemetry-chart__tooltip-hint">Clicca per selezionare</span></div>}
      <div className="telemetry-chart__unit">{unit}</div>
    </div>}
  </section>
}

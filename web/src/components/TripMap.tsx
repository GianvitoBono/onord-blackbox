import { useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { GpsSample, TripEvent } from '../api'
import './TripMap.css'

type TripMapProps = {
  samples: GpsSample[]
  events?: TripEvent[]
  selectedSampleId?: string | null
  onPointHover?: (sample: GpsSample | null) => void
  onPointSelect?: (sample: GpsSample) => void
  onEventHover?: (event: TripEvent | null) => void
  onEventSelect?: (event: TripEvent) => void
}

const defaultTileUrl = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const tileUrl = import.meta.env.VITE_MAP_TILE_URL || defaultTileUrl
const tileAttribution = import.meta.env.VITE_MAP_ATTRIBUTION || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'

export default function TripMap({ samples, events = [], selectedSampleId, onPointHover, onPointSelect, onEventHover, onEventSelect }: TripMapProps) {
  const hostRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const routeLayerRef = useRef<L.LayerGroup | null>(null)
  const selectedLayerRef = useRef<L.LayerGroup | null>(null)
  const eventLayerRef = useRef<L.LayerGroup | null>(null)
  const lastRouteRef = useRef<string>('')
  const [zoomRevision, setZoomRevision] = useState(0)
  const samplesRef = useRef<GpsSample[]>([])
  const projectedRef = useRef<L.Point[]>([])
  const callbacksRef = useRef({ onPointHover, onPointSelect, onEventHover, onEventSelect })
  callbacksRef.current = { onPointHover, onPointSelect, onEventHover, onEventSelect }

  const routeKey = useMemo(() => samples.map((sample) => sample.sampleId).join('|'), [samples])

  useEffect(() => {
    if (!hostRef.current || mapRef.current) return

    const canvasRenderer = L.canvas({ padding: 0.5 })
    const map = L.map(hostRef.current, {
      renderer: canvasRenderer,
      zoomControl: true,
      scrollWheelZoom: true,
      preferCanvas: true,
    }).setView([45.4642, 9.19], 6)

    L.tileLayer(tileUrl, {
      attribution: tileAttribution,
      maxZoom: 19,
    }).addTo(map)

    const routeLayer = L.layerGroup().addTo(map)
    const selectedLayer = L.layerGroup().addTo(map)
    const eventLayer = L.layerGroup().addTo(map)
    const closestSample = (point: L.Point) => {
      const route = samplesRef.current
      const projected = projectedRef.current
      let bestDistance = 14 * 14
      let best: GpsSample | null = null
      for (let index = 1; index < route.length; index++) {
        const start = projected[index - 1]
        const end = projected[index]
        const lengthSquared = start.distanceTo(end) ** 2
        const fraction = lengthSquared ? Math.max(0, Math.min(1, ((point.x - start.x) * (end.x - start.x) + (point.y - start.y) * (end.y - start.y)) / lengthSquared)) : 0
        const nearest = L.point(start.x + fraction * (end.x - start.x), start.y + fraction * (end.y - start.y))
        const distance = point.distanceTo(nearest) ** 2
        if (distance < bestDistance) {
          bestDistance = distance
          best = route[fraction < 0.5 ? index - 1 : index]
        }
      }
      return best
    }
    map.on('mousemove', event => callbacksRef.current.onPointHover?.(closestSample(map.latLngToLayerPoint(event.latlng))))
    map.on('mouseout', () => callbacksRef.current.onPointHover?.(null))
    map.on('click', event => {
      const sample = closestSample(map.latLngToLayerPoint(event.latlng))
      if (sample) callbacksRef.current.onPointSelect?.(sample)
    })
    map.on('zoomend', () => setZoomRevision(value => value + 1))

    const resizeObserver = new ResizeObserver(() => map.invalidateSize({ pan: false, debounceMoveend: true }))
    resizeObserver.observe(hostRef.current)
    mapRef.current = map
    routeLayerRef.current = routeLayer
    selectedLayerRef.current = selectedLayer
    eventLayerRef.current = eventLayer

    return () => {
      callbacksRef.current.onPointHover?.(null)
      resizeObserver.disconnect()
      map.remove()
      mapRef.current = null
      routeLayerRef.current = null
      selectedLayerRef.current = null
      eventLayerRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const layer = eventLayerRef.current
    if (!map || !layer) return
    layer.clearLayers()

    for (const event of events || []) {
      if (!Number.isFinite(event.latitude) || !Number.isFinite(event.longitude)) continue
      const isStart = event.kind === 'stop_start'
      const marker = L.circleMarker([event.latitude, event.longitude], {
        renderer: map.options.renderer,
        radius: 7,
        color: '#f7faf8',
        weight: 2,
        fillColor: isStart ? '#e6a83c' : '#d66e59',
        fillOpacity: 1,
        bubblingMouseEvents: false,
      }).addTo(layer)
      marker.bindTooltip(isStart ? 'Inizio sosta' : 'Fine sosta', { direction: 'top', offset: [0, -6] })
      marker.on('mouseover', () => callbacksRef.current.onEventHover?.(event))
      marker.on('mouseout', () => callbacksRef.current.onEventHover?.(null))
      marker.on('click', () => callbacksRef.current.onEventSelect?.(event))
    }
  }, [events])

  useEffect(() => {
    const map = mapRef.current
    const layer = routeLayerRef.current
    if (!map || !layer) return

    layer.clearLayers()
    const validSamples = samples.filter((sample) => Number.isFinite(sample.latitude) && Number.isFinite(sample.longitude))
    samplesRef.current = validSamples
    projectedRef.current = validSamples.map(sample => map.latLngToLayerPoint([sample.latitude, sample.longitude]))
    if (!validSamples.length) {
      callbacksRef.current.onPointHover?.(null)
      lastRouteRef.current = routeKey
      return
    }

    const latLngs = validSamples.map((sample) => L.latLng(sample.latitude, sample.longitude))
    if (latLngs.length > 1) {
      L.polyline(latLngs, { color: '#fff', weight: 9, opacity: 0.9, interactive: false, lineCap: 'round', lineJoin: 'round' }).addTo(layer)
      L.polyline(latLngs, { color: '#087d75', weight: 5, opacity: 1, interactive: false, lineCap: 'round', lineJoin: 'round' }).addTo(layer)
      let distanceFromArrow = 0
      for (let index = 1; index < latLngs.length; index++) {
        const start = map.latLngToLayerPoint(latLngs[index - 1])
        const end = map.latLngToLayerPoint(latLngs[index])
        const distance = start.distanceTo(end)
        if (distance < 2) continue
        let cursor = 110 - distanceFromArrow
        while (cursor < distance) {
          const fraction = cursor / distance
          const at = L.latLng(latLngs[index - 1].lat + (latLngs[index].lat - latLngs[index - 1].lat) * fraction, latLngs[index - 1].lng + (latLngs[index].lng - latLngs[index - 1].lng) * fraction)
          const angle = Math.atan2(end.y - start.y, end.x - start.x) * 180 / Math.PI
          L.marker(at, { interactive: false, keyboard: false, icon: L.divIcon({ className: 'route-arrow', html: `<span style="transform:rotate(${angle}deg)">➤</span>`, iconSize: [24, 24], iconAnchor: [12, 12] }) }).addTo(layer)
          cursor += 110
        }
        distanceFromArrow = (distanceFromArrow + distance) % 110
      }
    }
    eventLayerRef.current?.eachLayer((eventMarker) => {
      if (eventMarker instanceof L.Path) eventMarker.bringToFront()
    })

    if (routeKey !== lastRouteRef.current) {
      const bounds = L.latLngBounds(latLngs)
      if (bounds.isValid()) map.fitBounds(bounds.pad(latLngs.length === 1 ? 0.04 : 0.12), { maxZoom: 16, animate: false })
      lastRouteRef.current = routeKey
    }
  }, [samples, routeKey, zoomRevision])

  useEffect(() => {
    const map = mapRef.current
    const selectedLayer = selectedLayerRef.current
    if (!map || !selectedLayer) return

    selectedLayer.clearLayers()
    if (!selectedSampleId) return
    const selected = samples.find((sample) => sample.sampleId === selectedSampleId)
    if (!selected || !Number.isFinite(selected.latitude) || !Number.isFinite(selected.longitude)) return

    // Selection stays in the detail dialog; the route itself remains free of GPS dots.
    eventLayerRef.current?.eachLayer((eventMarker) => {
      if (eventMarker instanceof L.Path) eventMarker.bringToFront()
    })
  }, [samples, selectedSampleId])

  return (
    <div className={`trip-map${tileUrl === defaultTileUrl ? ' trip-map--osm-default' : ''}`} aria-label="Mappa del percorso">
      <div className="trip-map__canvas" ref={hostRef} />
      {!samples.length && <div className="trip-map__empty">Seleziona un viaggio per vedere il percorso</div>}
    </div>
  )
}

import { useEffect, useMemo, useRef } from 'react'
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
    map.on('mouseout', () => callbacksRef.current.onPointHover?.(null))

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
    if (!validSamples.length) {
      callbacksRef.current.onPointHover?.(null)
      lastRouteRef.current = routeKey
      return
    }

    const latLngs = validSamples.map((sample) => L.latLng(sample.latitude, sample.longitude))
    if (latLngs.length > 1) {
      L.polyline(latLngs, { color: '#237f75', weight: 4, opacity: 0.88, lineCap: 'round', lineJoin: 'round' }).addTo(layer)
    }

    for (const sample of validSamples) {
      const marker = L.circleMarker([sample.latitude, sample.longitude], {
        renderer: map.options.renderer,
        radius: 4,
        color: '#f8fbfa',
        weight: 1,
        fillColor: '#176f68',
        fillOpacity: 1,
        bubblingMouseEvents: false,
      }).addTo(layer)
      marker.on('mouseover', () => callbacksRef.current.onPointHover?.(sample))
      marker.on('mouseout', () => callbacksRef.current.onPointHover?.(null))
      marker.on('click', () => callbacksRef.current.onPointSelect?.(sample))
    }
    eventLayerRef.current?.eachLayer((eventMarker) => {
      if (eventMarker instanceof L.Path) eventMarker.bringToFront()
    })

    if (routeKey !== lastRouteRef.current) {
      const bounds = L.latLngBounds(latLngs)
      if (bounds.isValid()) map.fitBounds(bounds.pad(latLngs.length === 1 ? 0.04 : 0.12), { maxZoom: 16, animate: false })
      lastRouteRef.current = routeKey
    }
  }, [samples, routeKey])

  useEffect(() => {
    const map = mapRef.current
    const selectedLayer = selectedLayerRef.current
    if (!map || !selectedLayer) return

    selectedLayer.clearLayers()
    if (!selectedSampleId) return
    const selected = samples.find((sample) => sample.sampleId === selectedSampleId)
    if (!selected || !Number.isFinite(selected.latitude) || !Number.isFinite(selected.longitude)) return

    L.circleMarker([selected.latitude, selected.longitude], {
      renderer: map.options.renderer,
      radius: 8,
      color: '#fff',
      weight: 3,
      fillColor: '#d35135',
      fillOpacity: 1,
      interactive: false,
    }).addTo(selectedLayer)
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

import type { MetricDefinition, MetricSample } from '../api'

const derived: Record<string, { source: string; unit: string; decode: (raw: number) => number | null }> = {
  'obd.pid.0134.equivalence_ratio': {
    source: 'obd.pid.0134', unit: 'ratio',
    decode: raw => validRaw(raw) ? ((raw >>> 16) & 0xffff) / 32768 : null,
  },
  'obd.pid.0134.current_ma': {
    source: 'obd.pid.0134', unit: 'mA',
    decode: raw => validRaw(raw) ? (raw & 0xffff) / 256 - 128 : null,
  },
  'obd.pid.018e.friction_torque_pct': {
    source: 'obd.pid.018e', unit: '%',
    decode: raw => Number.isInteger(raw) && raw >= 0 && raw <= 255 ? raw - 125 : null,
  },
}

function validRaw(raw: number) {
  return Number.isInteger(raw) && raw >= 0 && raw <= 0xffffffff
}

export function withHistoricalMetrics(catalog: MetricDefinition[]): MetricDefinition[] {
  const added = Object.entries(derived)
    .filter(([name, field]) => !catalog.some(metric => metric.name === name)
      && catalog.some(metric => metric.name === field.source && metric.unit === 'raw_unsigned_integer'))
    .map(([name, field]) => ({ name, unit: field.unit }))
  return [...catalog, ...added]
}

export function historicalSource(name: string) {
  return derived[name]?.source ?? name
}

export function decodeHistoricalSamples(name: string, samples: MetricSample[]): MetricSample[] {
  const field = derived[name]
  if (!field) return samples
  return samples.flatMap(sample => {
    const value = field.decode(sample.value)
    return value == null ? [] : [{ ...sample, name, unit: field.unit, value }]
  })
}

export function isHistoricalMetric(name: string) {
  return name in derived
}

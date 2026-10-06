import type { MetricDefinition } from '../api'
import { metricLabel } from './format'

export type MetricGroup = 'Guida' | 'Motore' | 'Aria e turbo' | 'Carburante' | 'Emissioni' | 'Alimentazione' | 'Altri segnali'
type Guide = { title: string; explanation: string; group: MetricGroup; note?: string }

const guides: Record<string, Guide> = {
  'obd.pid.0104': { title: 'Carico motore', explanation: 'Quota di carico calcolata dalla centralina. Sale quando chiedi più lavoro al motore.', group: 'Motore' },
  'obd.pid.0105': { title: 'Temperatura motore', explanation: 'Temperatura del liquido di raffreddamento. Il profilo mostra il riscaldamento lungo il viaggio.', group: 'Motore' },
  'obd.pid.010b': { title: 'Pressione nel collettore', explanation: 'Pressione assoluta dell’aria aspirata: comprende la pressione atmosferica.', group: 'Aria e turbo', note: 'Per la pressione relativa confronta questo valore con la barometrica.' },
  'obd.pid.010c': { title: 'Giri motore', explanation: 'Velocità di rotazione del motore rilevata dalla centralina.', group: 'Motore' },
  'obd.pid.010d': { title: 'Velocità veicolo', explanation: 'Velocità riportata dalla centralina; può differire dalla misura GPS.', group: 'Guida' },
  'obd.pid.0110': { title: 'Aria aspirata', explanation: 'Massa d’aria che entra nel motore ogni secondo. Segue regime e carico.', group: 'Aria e turbo' },
  'obd.pid.012f': { title: 'Livello carburante', explanation: 'Stima del livello nel serbatoio riportata dalla centralina.', group: 'Carburante' },
  'obd.pid.0133': { title: 'Pressione atmosferica', explanation: 'Riferimento barometrico assoluto. Può restare quasi costante lungo un viaggio.', group: 'Aria e turbo' },
  'obd.pid.0142': { title: 'Tensione centralina', explanation: 'Tensione elettrica letta dalla centralina, utile per vedere alimentazione e ricarica.', group: 'Alimentazione' },
  'obd.pid.0149': { title: 'Pedale acceleratore D', explanation: 'Posizione di uno dei canali del pedale. Non coincide sempre con l’apertura della farfalla.', group: 'Guida' },
  'obd.pid.014a': { title: 'Pedale acceleratore E', explanation: 'Secondo canale della posizione del pedale acceleratore.', group: 'Guida' },
  'obd.pid.0162': { title: 'Coppia effettiva', explanation: 'Percentuale della coppia di riferimento dichiarata dalla centralina.', group: 'Motore', note: 'Non è la coppia alle ruote.' },
  'obd.pid.0163': { title: 'Coppia di riferimento', explanation: 'Valore di riferimento usato dalla centralina per le percentuali di coppia.', group: 'Motore', note: 'Non è la coppia istantanea.' },
  'obd.pid.0134.equivalence_ratio': { title: 'Rapporto aria/carburante O₂', explanation: 'Rapporto equivalente rilevato dalla sonda O₂ 1, decodificato dai byte del PID 0134.', group: 'Emissioni' },
  'obd.pid.0134.current_ma': { title: 'Corrente sonda O₂', explanation: 'Corrente della sonda O₂ 1, decodificata separatamente dal rapporto aria/carburante.', group: 'Emissioni' },
  'obd.pid.018e.friction_torque_pct': { title: 'Attriti motore', explanation: 'Percentuale della coppia di riferimento assorbita dagli attriti interni.', group: 'Motore' },
  'obd.calc.engine_torque_nm': { title: 'Coppia motore stimata', explanation: 'Coppia effettiva percentuale moltiplicata per la coppia di riferimento.', group: 'Motore', note: 'Stima OBD, non misura della coppia alle ruote.' },
  'obd.calc.manifold_gauge_pressure_kpa': { title: 'Pressione relativa collettore', explanation: 'Pressione collettore meno pressione atmosferica.', group: 'Aria e turbo', note: 'Può essere negativa al minimo; non è una misura diretta del turbo.' },
  'gps.speed_mps': { title: 'Velocità GPS', explanation: 'Velocità ricavata dalla posizione del telefono; confrontabile con quella OBD.', group: 'Guida' },
}

const groupOrder: MetricGroup[] = ['Guida', 'Motore', 'Aria e turbo', 'Carburante', 'Emissioni', 'Alimentazione', 'Altri segnali']
export { groupOrder }

export function isEncoded(metric: MetricDefinition) {
  return metric.unit === 'raw_unsigned_integer' || metric.unit === 'bitfield' || metric.unit === 'code'
}

export function guideFor(metric: MetricDefinition): Guide {
  const name = metric.name.toLowerCase()
  const known = guides[name]
  if (known) return known
  if (isEncoded(metric)) return {
    title: metricLabel(metric), group: 'Altri segnali',
    explanation: 'Risposta OBD codificata. Contiene più campi o flag; manca una decodifica verificata per mostrarla come misura fisica.',
    note: 'Disponibile nel catalogo tecnico, esclusa dai grafici.',
  }
  const group: MetricGroup = name.startsWith('gps.') ? 'Guida' : name.includes('fuel') || name.includes('rail') ? 'Carburante'
    : name.includes('air') || name.includes('map') || name.includes('boost') || name.includes('turbo') ? 'Aria e turbo'
      : name.includes('volt') || name.includes('battery') || name.includes('power') ? 'Alimentazione'
        : name.startsWith('obd.') ? 'Motore' : 'Altri segnali'
  return { title: metricLabel(metric), group, explanation: metric.description && metric.description !== metric.name
    ? metric.description : 'Segnale registrato dal veicolo. Unità e origine sono mostrate nel pannello.' }
}

export function metricSource(name: string) {
  return name.startsWith('obd.calc.') ? 'Stima' : name.startsWith('gps.') ? 'GPS' : name.startsWith('device.') ? 'Telefono' : 'Centralina'
}

// Standard Mode 01 names for encoded replies. A name does not imply that the
// stored raw integer is a physical measurement or that every subfield exists.
const encodedPidNames: Record<string, string> = {
  '0101': 'Stato monitor emissioni e DTC',
  '0134': 'Sonda O₂ 1 · rapporto aria/carburante e corrente',
  '014f': 'Limiti dichiarati · sonda O₂ e pressione aspirazione',
  '0168': 'Temperatura aria aspirata · sensori multipli',
  '0169': 'EGR · comando ed errore',
  '016d': 'Controllo pressione carburante',
  '0171': 'Controllo geometria variabile turbo',
  '0178': 'Temperatura gas di scarico · banco 1',
  '017a': 'DPF · pressione differenziale',
  '0183': 'Sensore NOx',
  '0185': 'Sistema reagente NOx',
  '0188': 'Sistema SCR · limitazioni',
  '018b': 'Post-trattamento diesel',
  '018e': 'Coppia assorbita da attriti motore',
  '018f': 'Sensore particolato',
  '0192': 'Controllo sistema carburante',
  '019d': 'Portata carburante motore',
  '01a1': 'Sensore NOx · dato corretto',
}

const decodedFieldNames: Record<string, string> = {
  '0134.equivalence_ratio': 'Sonda O₂ 1 · rapporto aria/carburante',
  '0134.current_ma': 'Sonda O₂ 1 · corrente',
  '018e.friction_torque_pct': 'Coppia assorbita da attriti motore',
}

export function standardPidName(name: string): string | undefined {
  const field = name.match(/^obd\.pid\.(01[0-9a-f]{2}\.[a-z0-9_]+)$/i)?.[1].toLowerCase()
  if (field) return decodedFieldNames[field]
  const code = name.match(/^obd\.pid\.(01[0-9a-f]{2})$/i)?.[1].toLowerCase()
  return code ? encodedPidNames[code] : undefined
}

export function rawPidValue(value: number): string {
  if (!Number.isSafeInteger(value) || value < 0) return String(value)
  return `0x${value.toString(16).toUpperCase()}`
}

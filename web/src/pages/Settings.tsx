import type { Dispatch, SetStateAction } from 'react'
import { formatDate } from './format'

type Props = { theme: 'light' | 'dark'; setTheme: Dispatch<SetStateAction<'light' | 'dark'>>; signedIn: string; updatedAt: Date | null }

export default function Settings({ theme, setTheme, signedIn, updatedAt }: Props) {
  return (
    <>
<div className="page-heading">
<div>
<span className="eyebrow">WORKSPACE</span>
<h1>Impostazioni</h1>
<p>Configurazione e riferimenti della dashboard.</p>
</div>
</div>
<div className="settings-layout">
<section>
<span className="eyebrow">ASPETTO</span>
<h2>Tema</h2>
<p>Adatta la dashboard alla luce dell’ambiente.</p>
<div className="theme-switch">
<button className={theme === 'light' ? 'selected' : ''} onClick={() => setTheme('light')}>☀ Chiaro</button>
<button className={theme === 'dark' ? 'selected' : ''} onClick={() => setTheme('dark')}>☾ Scuro</button>
</div>
</section>
<section>
<span className="eyebrow">CONNESSIONE</span>
<h2>Origine dati</h2>
<dl>
<div>
<dt>API</dt>
<dd>
<code>
{import.meta.env.VITE_API_BASE_URL || 'Stessa origine'}
</code>
</dd>
</div>
<div>
<dt>Sessione</dt>
<dd>
{signedIn}
</dd>
</div>
<div>
<dt>Aggiornamento</dt>
<dd>Manuale · {updatedAt ? formatDate(updatedAt.toISOString()) : '—'}
</dd>
</div>
</dl>
</section>
<section>
<span className="eyebrow">COME USARLA</span>
<h2>Esplora i dati</h2>
<p>Seleziona un mezzo in alto. Apri un viaggio, passa sul tracciato per vedere velocità e coordinate, clicca un punto per leggere telemetria vicina. Nel grafico trascina per zoom e seleziona un istante per localizzarlo sulla mappa.</p>
<a href="#explore" className="text-link">Apri Esplora →</a>
</section>
</div>
</>
  )
}

# Lettura campioni OBD reali — 6 ottobre 2026

Viaggio Giulietta da 4,4 km, letto tramite API dashboard. Il catalogo contiene 48 metriche; 16 PID Mode 01 sono salvati con unità `raw_unsigned_integer`. Il numero decimale rappresenta i byte della risposta concatenati in ordine big endian, non una misura fisica.

| PID | Dati osservati | Interpretazione |
| --- | --- | --- |
| `010B` MAP | 227 campioni, 27–150 kPa assoluti | Pressione collettore. |
| `0133` barometrica | 227 campioni, 100 kPa costanti | Riferimento atmosferico; 150 kPa assoluti corrispondono a circa 50 kPa relativi quando le letture sono contemporanee. |
| `0134` sonda O₂ | 24 campioni, interi 715.096.063–4.294.935.582 | Quattro byte: primi due / 32768 = rapporto equivalente (0,333–2,000); ultimi due / 256 − 128 = corrente (circa −0,004–4,117 mA). Decodifica storica disponibile nella dashboard. |
| `0171` geometria variabile | 24 campioni, es. `0x07CCCD00` | Byte multipli. Primo byte sempre `0x07`; secondo 152–244; terzo 95–249; quarto zero. Il grafico dell'intero 130.862.336 non è una posizione turbo. Campi da verificare con risposta ELM completa e specifica. |
| `018E` attrito motore | 24 campioni, byte 131–135 | Formula standard A − 125: 6–10% della coppia di riferimento. Decodifica storica disponibile nella dashboard. |

La dashboard mostra i campi fisici verificati e sposta i PID codificati in un catalogo tecnico esportabile. Il backend conserva ancora solo intero, valore e tempo per questi PID: per decodifiche più ampie servono byte originali, lunghezza e ECU sorgente. Non assegnare unità come `%` o `kPa` a `0171`, `017A`, `0183` o simili senza verificare i campi.

Fonti: [CSS Electronics, tabella PID OBD2](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979) per struttura e convenzione big endian; [datasheet ELM327](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf) per formato delle risposte Mode 01. La formula `0134` è già implementata nel parser Android del progetto.

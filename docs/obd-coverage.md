# Copertura OBD della Giulietta

Car Scanner mostra molti parametri perché combina PID standard, profili specifici del costruttore, valori calcolati e, talvolta, centraline diverse. Il numero di voci dell'app non equivale al numero di sensori leggibili dal generico Mode 01. La Giulietta 2020 1.6 JTDm2 e il dongle ELM327 vanno interrogati fisicamente per sapere quali PID sono disponibili. Nessun PID assente va registrato come zero.

## Acquisizione

1. Interrogare le bitmap Mode 01 (`0100`, `0120`, `0140`, `0160` e successive quando il bit di continuazione è presente). Registrare PID supportati, errori, tempi di risposta e identificazione dell'adattatore. Non supporre che ogni PID standard sia implementato dalla ECU.
2. Decodificare e conservare ogni parametro numerico standard implementato nel catalogo: motore, aria/aspirazione, carburante, emissioni, temperature, tensioni, coppia, distanze e contatori. Campioni indipendenti con timestamp di lettura, unità canonica e codice PID. Supportare valori sparsi.
3. Interrogare i PID a priorità: velocità, RPM, carico, MAP e coppia più spesso; temperature, carburante, contatori e diagnostica meno spesso. Un ELM327 seriale serve una richiesta per volta. Esporre frequenze e timeout effettivi nella diagnostica; non promettere 1 Hz per ogni voce quando il catalogo cresce.
4. Salvare localmente prima del sync. In backend, ogni metrica numerica usa una definizione e serie TimescaleDB; GPS conserva coordinate PostGIS con clock indipendente. Correlare per intervallo e tolleranza temporale, senza copiare coordinate su ogni lettura OBD.

## Grandezze richieste

| Grandezza | Fonte standard possibile | Significato |
| --- | --- | --- |
| Velocità | `010D` | Velocità veicolo dalla ECU; distinta da GPS. |
| Carico motore | `0104`, `0143` | Carico calcolato e assoluto: grandezze diverse. |
| Pressione aspirazione | `010B` | MAP assoluta in kPa, non pressione turbo relativa. |
| Pressione ambiente | `0133` | Pressione barometrica assoluta in kPa. |
| Sovrapressione stimata | `MAP - BARO` | Pressione relativa nel collettore, solo con campioni recenti di entrambe le fonti; può essere negativa. Serie `obd.calc.manifold_gauge_pressure_kpa`. Non è pressione turbo misurata direttamente. |
| Coppia richiesta/effettiva | `0161`, `0162` | Percentuali della coppia di riferimento, se esposte dalla ECU. |
| Coppia di riferimento | `0163` | Nm; combinata con `0162` consente stima della coppia effettiva (`obd.calc.engine_torque_nm`). Non confondere con coppia alle ruote. |
| MAF, temperature, rail, EGR, DPF | PID standard quando disponibili; altri richiedono profilo specifico | Nome e unità vanno legati a formula e sorgente verificata. |

I segnali produttore, soprattutto DPF, rigenerazioni, pressione turbo richiesta/effettiva, marcia, correzioni iniettori e dati di altre centraline, richiedono richieste diagnostiche e formule specifiche Alfa/Fiat. Aggiungere un profilo Giulietta separato solo dopo acquisizione non invasiva delle risposte e verifica di indirizzi, scaling, unità e disponibilità. Il normale OBD emissioni non garantisce questi dati; inventare ID o formule produce grafici falsi. Nessun comando di scrittura/attuazione ECU è previsto.

Ricerca di richieste Mode 22 candidate, con fonti e limiti: [spike PID estesi Giulietta](giulietta-extended-pid-spike.md).

Lettura della scansione Mode 09 e mappatura dei PID `01xx` già visibili: [spike Mode 01 del 6 ottobre 2026](giulietta-mode01-scan-2026-10-06.md).

La dashboard deve mostrare catalogo effettivamente raccolto, unità, sorgente e assenza dati. Un valore calcolato deve essere identificabile come tale.

Riferimenti: [SAE J1979](https://saemobilus.sae.org/standards/j1979da_201702-j1979-da-digital-annex-e-e-diagnostic-test-modes), [tabella PID e formule](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979).

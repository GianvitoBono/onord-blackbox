# Spike: PID standard letti dalla Giulietta, 6 ottobre 2026

Questa nota usa il report Mode 09 incollato dall'utente e lo screenshot della telemetria. Gli identificatori `01xx` sono **PID OBD Mode 01**, non OID del database. Un PID può contenere più grandezze e flag; il numero `raw_unsigned_integer` attualmente salvato dall'app è soltanto la concatenazione big endian dei byte ricevuti, **non** una misura fisica.

## Identificazione ECU: cosa prova

- ELM327 v1.5, protocollo selezionato `A7`: ISO 15765-4 CAN a 29 bit, 500 kbit/s.
- `0900` ha prodotto due risposte: `18DAF101 = 14620000` e `18DAF110 = 54620000`. La bitmap è dei servizi **Mode 09** (identificazione ECU), non dei sensori Mode 01. La seconda risposta dichiara anche `0902` (VIN); entrambe dichiarano `0904`, `0906`, `090A`.
- `0904`, `0906`, `090A` si fermano al primo frame ISO-TP. Il successivo tentativo fisico ha restituito `7F 09 11` per `18DA01F1` e `NO DATA` per `18DA10F1`. Nessun CALID, CVN o nome ECU completo è stato acquisito: famiglia e software Bosch non sono ancora identificati. La risposta negativa fisica non cancella le risposte funzionali già ricevute. Indagare gestione flow control/clone ELM prima di usare il report per identificare il profilo ECU.

## Cosa si vede già nello screenshot

| PID | Significato standard | Stato nel prodotto |
| --- | --- | --- |
| `0104`, `0105`, `010B`, `010C`, `010D`, `0110` | Carico, refrigerante, MAP assoluta, RPM, velocità, massa aria | Già decodificati |
| `012F`, `0130`, `0131`, `0133`, `0142` | Carburante, avviamenti/distanza da azzeramento DTC, barometrica, tensione ECU | Già decodificati |
| `0145`, `0146`, `0149`, `014A`, `014C` | Farfalla relativa, temperatura esterna, pedale acceleratore D/E, attuatore farfalla | Già decodificati |
| `0162`, `0163` | Percentuale coppia effettiva, coppia di riferimento | Già decodificati; `350 Nm` è **riferimento**, non coppia istantanea |
| `0101`, `0113` | Stato monitor emissioni/DTC, presenza sonde O₂ | Bitfield, non grandezza continua |
| `0134` | Sonda O₂: rapporto aria/carburante **e** corrente | Da app 0.1.8, due serie decodificate solo da risposta completa; storico precedente raw |
| `014F` | Limiti dichiarati per sonda O₂ e MAP | Quattro campi, non letture live |
| `0168`, `0169` | Sensori temperatura aspirazione multipli; comando/errore EGR | Campi con disponibilità/flag, oggi raw |
| `016D`, `0171` | Controllo pressione carburante; controllo geometria variabile turbo | Strutture, oggi raw. `0171` **non è** pressione turbo misurata |
| `0178`, `017A` | Temperatura gas di scarico; pressione differenziale DPF | Strutture con più campi/flag, oggi raw |
| `0183`, `0185`, `0188`, `018B`, `018F` | NOx, reagente NOx, SCR, post-trattamento diesel, particolato | Strutture, oggi raw |
| `018E` | Coppia percentuale assorbita da attriti motore | Da app 0.1.8: `A - 125` %. Raw `131` nello screenshot corrisponderebbe a **6%**, se la risposta contiene proprio quel byte |
| `0192`, `019D`, `01A1` | Controllo carburante, portata carburante, NOx corretto | Strutture, oggi raw |

Fonte per nomi e formule Mode 01: [tabella tecnica CSS Electronics](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979), basata sui PID standard [SAE J1979-DA](https://saemobilus.sae.org/standards/j1979da_201702-j1979-da-digital-annex-e-e-diagnostic-test-modes). La tabella CSS lascia senza formula molti PID diesel composti: per questi occorre la specifica completa e una risposta grezza integra, non una scala inventata.

Esempio concreto: `0134 = 4294935475 = 0xFFFF83B3`. Se sono **esattamente** quattro byte dati (`FF FF 83 B3`), i due campi standard danno `0xFFFF / 32768 ≈ 2,000` per il rapporto equivalente e `0x83B3 / 256 - 128 ≈ 3,70 mA` per la corrente. L'app oggi non salva lunghezza, header ECU e risposta grezza per ogni lettura; quindi questa è una decodifica **condizionale**, non una validazione del sensore.

Altro controllo: `0101 = 977664 = 0xEEB00` nello screenshot conserva solo tre byte significativi, mentre il PID standard richiede quattro byte. Può mancare uno zero iniziale oppure la risposta è troncata: il numero salvato da solo non permette di distinguere. `014F` conserva valori massimi/capacità, non valori istantanei. Grafici delle serie raw producono interpretazioni fuorvianti.

Il `0133 = 100 kPa` è pressione atmosferica. Al minimo, `010B = 66 kPa` e `MAP - BARO = -34 kPa`: depressione nel collettore, non sovralimentazione turbo. `0162 = 0%` e `0163 = 350 Nm` danno coppia calcolata `0 Nm` per quel campione, senza implicare che il motore non stia girando.

## Implementazione successiva

1. Salvare per ogni risposta OBD Mode 01: PID richiesto, timestamp, ECU sorgente, byte dati esatti, lunghezza e stato. Per frame ISO-TP, ricomporre e validare sequenza/lunghezza prima del decode. Scartare letture tronche, non reinterpretarle come interi validi. Il [datasheet ELM327](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf) documenta header CAN e flow control.
2. Estendere il catalogo **per PID e sottocampo**: formule, unità, flag di disponibilità, range e provenienza. `018E` e i due campi di `0134` sono decodificati dall'app 0.1.8 in serie distinte; EGR, VGT, EGT, DPF e NOx richiedono layout verificato. Conservare in futuro i byte originali insieme ai campi derivati, così le formule si possono correggere senza perdere dati.
3. Raggruppare i dati per ECU, perché la scansione vede due rispondenti e la bitmap `0100`/successive dell'app oggi usa solo la prima risposta trovata. Unire i PID supportati senza confondere le risposte di ECU diverse.
4. Validare sotto carico, confrontando un campione grezzo e un valore mostrato da Car Scanner nello stesso istante. I DID proprietari `22xxxx` richiedono un'altra verifica: [spike Mode 22 Giulietta](giulietta-extended-pid-spike.md). Questa scansione Mode 09 non dimostra che quei DID siano disponibili sulla 1.6.

Nella web UI, i PID composti già storicizzati ricevono un nome standard e appaiono in esadecimale come **dato codificato**; i valori raw sono esclusi dalla selezione iniziale dei grafici. I nuovi `0134.equivalence_ratio`, `0134.current_ma` e `018e.friction_torque_pct` sono serie separate. Nessuna serie storica è stata riscalata retroattivamente.

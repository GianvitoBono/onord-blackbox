# Spike: PID estesi Alfa/Fiat per Giulietta 1.6 JTDm2 (2020)

Ricerca del 6 ottobre 2026. Obiettivo: trovare richieste di **sola lettura** per pressione turbo, DPF e altri segnali non esposti dal normale OBD Mode 01. Nessuna richiesta estesa è stata provata sulla vettura dell'utente; nessun PID qui sotto è abilitato automaticamente nell'app.

## Identificare prima la centralina

La [lista ufficiale MultiECUScan](https://www.multiecuscan.net/supportedvehicleslist.aspx) associa alla Giulietta 1.6 JTDm le famiglie Bosch **EDC16C39 CF5**, **EDC17C49 CF5+** ed **EDC17C69 CF6**. Per ciascuna elenca identificazione, errori e parametri tramite ELM. Anno e motore, da soli, non provano quale versione e quale software siano installati. MultiECUScan [SCAN mostra tipo modulo e codice ISO](https://www.multiecuscan.net/MultiecuscanUserGuide.pdf); raccogliere anche identificativi/calibrazione ECU, oscurando il VIN prima di condividere log.

Car Scanner [spiega che un PID esteso richiede comando, header ECU e formula](https://www.carscanner.info/2019/07/). Un `22xxxx` non compare nelle bitmap standard `0100`/`0120`: queste descrivono i PID Mode 01, non l'intero catalogo del costruttore.

## Candidati documentati da acquisizioni su Giulietta

Fonte primaria: [log e prove su Giulietta 2.0 JTDm](https://torque-bhp.com/community/main-forum/alfa-romeo-giulietta-supercharging-pressure-pids/), più [log e riscontri DPF dello stesso proprietario](https://torque-bhp.com/community/main-forum/alfa-romeo-giulietta-dpf-pids/). Il proprietario ha registrato risposte ECU con ID `18DAF110` usando richieste verso `18DA10F1` (header breve `DA10F1` in Torque). **Trasferibilità alla 1.6 del 2020 non dimostrata.** Codici e formule seguenti sono ipotesi da validare, non definizioni di prodotto.

| Segnale candidato | Richiesta Mode 22 | Evidenza e decodifica candidata | Stato 1.6 |
| --- | --- | --- | --- |
| Sovralimentazione reale | `22195A` | Risposta pubblicata `62195A82F4`; secondo autore e interlocutore `u16(A,B) - 32768` dà **756 mbar**. La conversione aggiuntiva usata per allineare l'indicatore Alfa Dynamic (`-0,8 bar`) è empirica e non va copiata. | Non verificato |
| Sovralimentazione richiesta | `221959` | Stesso interlocutore propone questo identificatore e la formula `u16 - 32768` in mbar; manca coppia richiesta/risposta pubblicata. | Non verificato |
| Pressione atmosferica estesa | `221956` | Identificatore e formula `u16 - 32768` in mbar proposti nello stesso thread; confrontare con `0133` standard. | Non verificato |
| Pressione differenziale DPF | `2218E2` | Identificatore proposto nello stesso thread; verificare unità, offset e andamento con giri/carico. | Non verificato |
| Temperatura gas DPF | `2218DE` | Log `6218DE3401`; autore dichiara valida la formula `u16 × 0,02 − 40` °C dopo verifica su auto. | Non verificato |
| Distanza da ultima rigenerazione | `223807` | Log `6238070005E8`; autore usa `u24 × 0,1` km. | Non verificato |
| Intasamento DPF calcolato | `2218E4` | Log `6218E409FB`; autore riporta `u16 × 1000/65535` come “%”. Scala/unità insolite: sospendere etichetta percentuale finché non confrontata con strumento diagnostico. | Non verificato |
| Avanzamento rigenerazione DPF | `22380B` | Log `62380B0000`; discussione riporta formule **divergenti** (`u16 × 100/65535` e `u16 × 1000/65535`). Non equivale automaticamente a flag booleano “rigenerazione attiva”. | Non verificato |

`u16(A,B) = A × 256 + B`; `u24(A,B,C) = A × 65536 + B × 256 + C`. Nel thread DPF, i primi tentativi in Torque restituivano `NO DATA` finché non fu configurato l'header fisico. Un `NO DATA` resta comunque possibile su ECU diversa o servizio non disponibile.

## Header e limiti tecnici

Per CAN a 29 bit, il [datasheet ELM327](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf) documenta `AT SH` a quattro byte. Dalle trame pubblicate, richiesta candidata `18DA10F1`, risposta `18DAF110`. Non impostare quell'header finché protocollo e destinatario ECU non sono confermati; ripristinare l'header OBD normale dopo eventuale sessione diagnostica. Validare risposta positiva `62` + DID richiesto, ID ECU, lunghezza, frame multipli, risposta negativa `7F` e timestamp. L'attuale parser Mode 01 non basta per decodificare risposte Mode 22.

## Acquisizione necessaria sulla vettura

1. Da ferma, identificare ECU motore con SCAN/Info di MultiECUScan o strumento equivalente: famiglia, ISO code, ID calibrazione; oscurare VIN. Annotare protocollo ELM e adattatore.
2. Esportare elenco parametri visibili in Car Scanner/MultiECUScan con nomi e unità. Se possibile, acquisire log **grezzo** di poche richieste mirate e risposte, senza comandi di scrittura, reset, attuazione o rigenerazione.
3. Provare i candidati in allowlist, uno alla volta, solo dopo conferma di ECU e indirizzamento. Registrare risposta grezza e valore visualizzato dallo strumento nello stesso istante. Verificare motore spento, minimo e variazione sotto carico tramite log raccolto dal passeggero o a posteriori.
4. Accettare ciascun segnale solo dopo conferma di formula, unità, campo plausibile e comportamento sulla **EDC effettiva**. Separare valore misurato, richiesto e calcolato; conservare provenienza e profilo ECU. Non fare sweep cieco di DID proprietari.

**Esito:** fonti concrete trovate; EDC e PID della Giulietta 1.6 dell'utente ancora da verificare fisicamente. L'app ora identifica le risposte standard Mode 09 e conserva i dati grezzi con header ECU; prossima fase: acquisizione mirata Mode 22 dopo conferma del profilo compatibile.

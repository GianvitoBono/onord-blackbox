# Taskboard — Vehicle Blackbox

Stato al 2026-10-05. Le caselle indicano lavoro completato nel repository, non una build verificata su dispositivo. Docker Desktop ha completato lo spostamento di `Docker.raw` sul disco esterno.

## P0 — rendere utilizzabile il logger Android

- [x] Struttura Kotlin/Gradle, Room, servizio foreground persistente e ricevitore power dinamico.
- [x] State machine base e persistenza trip/campioni batteria.
- [x] SDK e wrapper disponibili; `:app:testDebugUnitTest :app:assembleDebug` riusciti con cache sul disco esterno.
- [x] Permessi runtime, avvio monitor da Activity visibile e recupero stato power all'avvio; impossibile garantire cold start da broadcast su Android stock.
- [x] Confermati via ADB modello AC2003, device Nord, Android 12/API 31; APK debug installato e MainActivity avviata.
- [ ] Verificare ordinamento eventi concorrenti, sopravvivenza servizio e restart dopo kill sul telefono.
- [x] GPS reale 1 Hz con Fused Location; request ferma in IDLE; precisione, quota, heading e velocità salvati.
- [ ] Collector GPS e power fake; test end-to-end: power on → campioni → breve interruzione → ripresa → chiusura.
- [ ] DataStore: grace period, intervallo GPS, device ID e preferenze sync.
- [ ] UI diagnostica completa: presenti permessi, configurazione sync e contatori Room; mancano stato live power/trip/GPS e errori.
- [ ] Log locali con retention e export; nessun dato posizione in log verbosi.
- [ ] Prova sul Nord reale con schermo spento, riavvio, perdita power e ottimizzazioni OxygenOS.

**Done:** APK installabile; trip GPS completo offline con riavvio e power cycle; nessuna perdita del trip già persistito; test del percorso fake e prova fisica documentata.

## P1 — contratto dati e backend minimo

- [x] Versione sviluppo PostGIS: TimescaleDB HA 2.30.0/PostgreSQL 17.11; migrazioni SQLx 1 e 2 applicate nel database locale sul disco esterno.
- [x] Catalogo metriche, teste di serie, campioni numerici hypertable e punto GPS PostGIS GiST; ingest v1 a doppia scrittura transazionale.
- [x] Letture API per metrica e correlazione geografica con intervallo, raggio, tolleranza e limite.
- [x] Ogni fix GPS ha `trip_id`, `delta_sec` e `delta_m` dal precedente; batch fuori ordine riparano successore e distanza totale tour.
- [ ] Backfill delle righe precedenti e verifica parità vecchie/nuove serie; letture metriche su dati reali ancora da misurare.
- [ ] Misurare ingest per batch su dati OBD reali; ottimizzare scritture metriche con bulk insert se SQL per campione limita throughput.
- [ ] Contratto JSON v1 documentato in `server/README.md`; OpenAPI ancora da generare.
- [x] Backend Rust Axum/Tokio/SQLx, health/readiness e pool Postgres; `cargo check --locked` riuscito.
- [ ] Autenticazione device via hash token e limiti batch implementati; TLS reverse proxy ancora da configurare.
- [x] `POST /api/v1/telemetry/batches`: validazione, ricevuta transazionale e ACK idempotente implementati; test DB da eseguire.
- [ ] Test integrazione con TimescaleDB: replay stesso batch, batch ID con payload diverso, dati fuori ordine, duplicati, guasto DB.
- [x] Android WorkManager: gate Wi-Fi, retry, batch ID/payload persistenti, ACK prima di `syncedAt`; build Android riuscita.
- [ ] Backfill offline di un mese simulato; monitorare throughput, dimensioni DB e tempi sync.

**Done:** stesso batch inviato più volte produce stesse righe e stesso ACK; batch alterato viene rifiutato; logger continua senza backend.

## P2 — OBD

- [x] Scelta iniziale BT classico SPP, permesso Bluetooth e selettore dispositivi già associati.
- [x] `ObdTransport` astratto e fake; ELM327 init, PID discovery e riconnessione. Timeout lettura socket da verificare con adattatore reale.
- [x] Parser per PID standard e bitmap supporto con test unitari; PID non supportati registrati come stato.
- [x] Persistenza locale letture PID con ID e timestamp; payload compatibile con contratto P1. Migrazione Room v3 e schema generato.
- [x] Catalogo Mode 01 ampliato, Room v4 con PID dinamici, scheduler prioritario e stime MAP−BARO/coppia; frontend mostra catalogo effettivamente raccolto.
- [ ] Profilo Alfa/Fiat specifico per Giulietta: identificare e verificare ID/formule da acquisizione reale, incluse metriche DPF e turbo richiesto/effettivo.
- [ ] Prove auto con dongle reale e misure di latenza/temperatura.

**Done:** disconnessione OBD non interrompe GPS/trip; PID mancanti restano assenti, non zero.

## P3 — dashboard web

- [x] React + TypeScript + Vite con client API tipizzato manualmente; `npm run build` riuscito.
- [ ] Dashboard veicoli/viaggi e traccia GPS presenti; mancano mappa, grafici OBD, salute dispositivo e stato sync.
- [ ] Query server con intervallo temporale, paginazione e downsampling; evitare download di mesi di campioni nel browser.
- [x] Login dashboard username/password Argon2id, sessioni revocabili in cookie HttpOnly; separato dai token dispositivo.
- [ ] Scope accesso per veicolo e rate limiting login prima di esposizione pubblica.
- [ ] Export CSV/JSON e cancellazione dati su richiesta.

**Done:** viaggio lungo consultabile senza caricare tutte le righe raw; accesso limitato a veicoli autorizzati.

## P4 — affidabilità e appliance

- [x] Compose server con API Rust, TimescaleDB/PostGIS, dashboard Caddy HTTPS, volumi persistenti e provisioning credenziali; immagini Docker costruite localmente.
- [ ] Deploy sul server, dominio/DNS, backup automatici e rate limiting login da configurare.

- [ ] Backup/ripristino TimescaleDB verificato; retention configurabile solo dopo scelta esplicita.
- [ ] Metriche ingest, backlog, errori, spazio DB, ultimo contatto dispositivo.
- [ ] Continuous aggregates per grafici di lungo periodo, dopo misure sulle query reali.
- [ ] Ricerca embedded/root/auto-boot e procedura separata (`embedded-mode.md`).

## Dipendenze

P0 precede prove in auto. Contratto P1 precede WorkManager e frontend. OBD P2 può avanzare in parallelo dopo stabilizzazione logger. Dashboard P3 usa API di lettura progettate su query reali. Nessuna policy di cancellazione automatica finché requisiti di conservazione non sono definiti.

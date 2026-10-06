# Backend, dashboard e dati veicolo

## Scelta stack

API Rust con Axum/Tokio, SQLx e PostgreSQL/TimescaleDB/PostGIS. Axum tiene HTTP semplice; SQLx permette query SQL esplicite e pool senza ORM pesante. Dashboard React + TypeScript + Vite: client statico leggero, API indipendente, niente SSR necessario per pannello privato.

```
OnePlus Nord Room → WorkManager → HTTPS → Rust API → TimescaleDB
                                      ↑
                         React/TypeScript dashboard
```

Room sul telefono resta source of truth finché server non conferma batch. Backend conserva copie confermate e viste per consultazione. API separa ingest device da lettura dashboard. Una sola istanza API basta inizialmente; reverse proxy gestisce TLS. Nessuna coda esterna finché misure non la giustificano.

## Modello

- `vehicles`: identità auto, nome/VIN opzionale, metadati modificabili. VIN non richiesto; non metterlo nei campioni.
- `devices`: installazione blackbox, chiave API hashata, veicolo attuale. `device_vehicle_assignments` conserva storico quando dispositivo cambia auto; `trips.vehicle_id` fotografa assegnazione al momento del viaggio.
- `trips`: una riga relazionale per viaggio, timestamp e ragioni start/end, distanza e stato. Query per auto/data semplici; Timescale non offre vantaggio evidente per poche righe trip rispetto a campioni.
- `ingest_batches`: ricevute idempotenti con `(device_id, batch_id)` univoco, hash payload e contatori. Scrittura insieme ai campioni in una transazione.
- `gps_samples`: hypertable temporale, una riga per fix, posizione PostGIS `geography(Point,4326)` generata e indice GiST.
- `obd_measurements` e `device_samples`: hypertable tipizzate mantenute per compatibilità API v1 e letture raw.
- `metric_definitions`: catalogo nomi/unità/tipi (`obd.pid.010c`, `device.battery_pct`, `gps.speed_mps`).
- `metric_series`: testa della serie con device, veicolo e tour storici; etichette JSONB controllate e a bassa cardinalità.
- `metric_samples`: hypertable numerica con tempo, sample ID e valore. Ingest scrive sia tabelle raw sia serie metriche nella stessa transazione.

GPS e OBD arrivano a frequenze diverse. Unire a posteriori per finestra temporale quando serve un grafico; evitare snapshot fittizi 1 Hz che duplicano valori OBD. Android mantiene una tabella locale larga e produce righe PID separate nel payload sync. Dettagli e query: [metriche e geografia](metrics-geospatial.md).

## Contratto ingest

`POST /api/v1/telemetry/batches` con token device, `schemaVersion`, `deviceId`, `batchId`, `trip`, array di GPS/OBD/device samples. Ogni sample ha UUID stabile generato sul telefono e `observedAt` UTC; il server registra anche `receivedAt`. Limitare numero righe e byte per batch; rifiutare timestamp/payload invalidi con errore strutturato. ACK include `batchId`, `accepted`, conteggi e versione. Nessun `syncedAt` locale prima dell'ACK corrispondente.

Transazione ingest: autorizzare device → serializzare per batch ID → cercare ricevuta → se stesso hash restituire ACK precedente → se hash diverso `409 Conflict` → inserire trip/campioni raw e metrici → verificare una riga per ogni insert → inserire ricevuta → commit → ACK. Hash su JSON canonico; batch ID stabile fra retry. Se connessione cade dopo commit, retry recupera ricevuta.

Timescale richiede che chiavi univoche delle hypertable includano colonna temporale di partizionamento. Perciò PK campioni include `(device_id, observed_at, sample_id)`; idempotenza globale del batch usa `ingest_batches` relazionale. Il server deve inoltre verificare che un sample ID riutilizzato con altro timestamp non venga trattato come normale dato nuovo, almeno entro il batch e nelle finestre operative definite. Vedere `backend-schema.sql` per schema iniziale.

## Lettura e operazioni

API lettura attuale: veicoli, tour, GPS raw, campioni per nome metrica e correlazione spaziale PostGIS con campione più vicino entro tolleranza. Query metriche/geografiche richiedono intervallo e limite. Cursor, downsampling server, stato device e ulteriori filtri sono futuri. Indici su tour/tempo, serie/tempo e punto GPS; aggiungere continuous aggregates solo quando query misurate lo richiedono. Dimensione chunk e compressione da tarare su volume reale. Retention non attiva per default; cancellare raw solo con policy esplicita e backup verificato.

Dashboard usa username/password Argon2id e sessione opaca in cookie HttpOnly, distinta dai token dispositivo. TLS, token device revocabili, scope per device/veicolo e rate limiting login restano necessari prima di esposizione pubblica. Coordinate precise: accesso minimo, export e cancellazione espliciti. Log API non includono payload GPS, password o token.

## Fonti tecniche

- [Timescale: unique indexes on hypertables](https://docs.timescale.com/use-timescale/latest/hypertables/hypertables-and-unique-indexes/)
- [Timescale: continuous aggregates](https://docs.timescale.com/use-timescale/latest/continuous-aggregates/create-a-continuous-aggregate/)
- [Timescale: retention policies](https://docs.timescale.com/use-timescale/latest/data-retention/create-a-retention-policy/)
- [Timescale: PostGIS](https://docs.timescale.com/use-timescale/latest/extensions/postgis/)
- [PostGIS: ST_DWithin e indice spaziale](https://postgis.net/documentation/tips/st-dwithin/)
- [Axum documentation](https://docs.rs/axum/latest/axum/)
- [React with TypeScript](https://react.dev/learn/typescript)
- [Vite templates](https://vite.dev/guide/)

# Vehicle Blackbox

Logger Android offline-first per OnePlus Nord originale AC2003 (Android 12, API 31), API Rust/TimescaleDB e dashboard React/TypeScript. GPS, sync Wi-Fi e lettura OBD-II standard via Bluetooth classico hanno una prima implementazione compilabile. APK debug installato e avviato sul telefono; logging GPS/OBD in auto ancora da verificare.

APK debug aggiornato: [opnord-blackbox-0.1.9-debug.apk](releases/opnord-blackbox-0.1.9-debug.apk) ([SHA-256](releases/opnord-blackbox-0.1.9-debug.apk.sha256)). Dalla schermata Android, **Acquisisci risposte OBD grezze** legge i PID Mode 01 osservati e conserva header ECU, risposta ELM, timestamp e payload ISO-TP completo quando disponibile; **Copia risposte OBD grezze** esporta il report JSON. La 0.1.8 aveva aggiunto la decodifica di `0134` e `018E` in serie distinte dai vecchi interi raw e messaggi diagnostici sul bond Bluetooth. La richiesta PIN ripetuta resta da verificare con l'adattatore. La scansione ECU riconosce header CAN a 29 bit separati e riprova richieste Mode 09 per singola ECU. La scansione DTC legge MIL e codici memorizzati/in attesa/permanenti, attribuisce le risposte CAN alle ECU e conserva risposte grezze. Ogni report resta in coda locale fino alla conferma del backend; **Diagnostica** nella web UI mostra storico e dettagli. Per questa funzione aggiornare backend e web: la migrazione 0010 viene applicata all'avvio.

- [Taskboard e criteri di completamento](docs/taskboard.md)
- [Architettura Android](docs/architecture.md)
- [Backend Rust, TimescaleDB e dashboard TypeScript](docs/backend-architecture.md)
- [Metriche, serie e query geospaziali](docs/metrics-geospatial.md)
- [Copertura OBD e limiti della Giulietta](docs/obd-coverage.md)
- [Schema SQL proposto](docs/backend-schema.sql)
- [Milestone originali](docs/milestones.md)
- [Deploy server con Docker Compose](docs/deploy-server.md)
- [Compose completo locale via HTTP](docs/compose-local.md)
- [Deploy con Nginx esistente](docs/deploy-nginx.md)

Struttura: `app/` Android; `server/` Rust API; `web/` React/TypeScript; `compose.yaml` TimescaleDB locale. Il contratto JSON esatto è in [server/README.md](server/README.md).
Per server Linux usare `compose.server.yaml`: include API, TimescaleDB/PostGIS e dashboard HTTPS; configurazione in `.env.server` separata dallo sviluppo locale.

## Ambiente Android su disco esterno

SDK: `/Volumes/External/Android/sdk` (`local.properties`, non versionato). Per build da terminale usare `scripts/gradle-external.sh :app:assembleDebug`: cache Gradle e temp sotto `.cache/` nel progetto sul disco esterno. In Android Studio impostare **Gradle user home** su `/Volumes/External/Projects/opnord-blackbox/.cache/gradle`; controllare anche SDK Manager e Gradle JDK. Evitare la cartella predefinita `~/.gradle` sul disco Mac. ADB ha confermato `ro.product.model=AC2003`, `ro.product.device=Nord`, Android `12`.

Per Cargo e npm usare `scripts/run-external.sh` come prefisso: imposta cache, target Rust e temp sotto `.cache/` sul disco esterno. Dati PostgreSQL/PostGIS risiedono in `.data/postgres-postgis`; il precedente `.data/postgres` è conservato. Docker Desktop deve avere anche **Disk image location** sul disco esterno: un bind mount PostgreSQL da solo non sposta cache immagini/build Docker.

## Avvio sviluppo locale

1. Copiare `.env.example` in `.env` e sostituire password/token di esempio; file ignorato da Git.
2. `docker compose up -d timescaledb`.
3. `set -a; . ./.env; set +a; scripts/run-external.sh cargo run --manifest-path server/Cargo.toml` (migrazioni automatiche).
4. Dopo migrazione, `python3 scripts/provision-dev.py` crea veicolo/device demo e salva credenziali in `.data/dev-credentials.json` (ignorate da Git).
5. `set -a; . ./.env; set +a; scripts/run-external.sh cargo run --manifest-path server/Cargo.toml --bin provision_dashboard` crea utente `admin`, password casuale Argon2id e salva username/password in `.data/dashboard-login.txt` (permessi `0600`, ignorato da Git). Per ruotarla usare lo stesso comando con `-- --reset`: invalida sessioni precedenti.
6. In altra shell: `cd web && ../scripts/run-external.sh npm install && ../scripts/run-external.sh npm run dev`. Vite inoltra `/api` al server locale. Accedere con username/password; browser riceve cookie HttpOnly, senza token da incollare.

API locale usa HTTP solo dietro loopback per sviluppo. App Android accetta endpoint HTTPS: per sincronizzare dal telefono serve un endpoint raggiungibile e TLS valido, oltre a token device provisionato. Logger GPS funziona offline senza backend. Il monitor apre un viaggio dopo movimento GPS confermato, registra inizio/fine sosta dopo due minuti fermo e chiude il viaggio dopo 90 minuti di sosta continua. Oscillazioni alimentazione USB non cambiano il viaggio. La sincronizzazione usa solo Wi-Fi e invia anche campioni di viaggi attivi; alla chiusura invia i metadati finali. Nella schermata Android sono visibili ultimo GPS, campioni ancora da inviare ed esito dell'ultimo sync; "Sincronizza ora" forza un nuovo tentativo. Avviare il monitor dalla schermata app dopo permessi posizione precisi/sempre; dopo force-stop serve riaprirlo manualmente. Aggiornare prima il backend (migrazione 0007), poi l'APK: i nuovi eventi sosta richiedono il backend aggiornato.

L'ID dispositivo è un UUID stabile, diverso dal nome del mezzo. Se viene cambiato, l'app rigenera i batch locali ancora da inviare con il nuovo ID; il backend riassocia automaticamente i viaggi esistenti quando il nuovo dispositivo è assegnato allo stesso mezzo. I campioni già ricevuti conservano l'ID dispositivo originale come provenienza. Aggiornare il backend prima di sincronizzare con un ID cambiato.

GPS e batteria vengono campionati ogni 2 s durante il viaggio. All'avvio della connessione OBD l'app legge i bitmap Mode 01 supportati e ruota tutti i PID dichiarati; quelli con formula nota hanno nome e unità, altri valori numerici semplici restano marcati `raw_unsigned_integer`. La schermata Android mostra quanti PID sono stati dichiarati, decodificati e letti. In **Esplora → Segnali del mezzo → Nome e unità** si possono correggere etichette e unità, preservate dal backend. La sola rinomina non applica formule di conversione ai valori raw. Sensori proprietari Alfa/Fiat richiedono identificazione di PID, richiesta e formula tramite acquisizione reale.

Con auto ferma, adattatore associato e quadro acceso, **Scansiona errori OBD motore** nell'app legge MIL e conteggio DTC (Mode 01 PID 01), codici memorizzati/in attesa/permanenti (Mode 03/07/0A) e una fotografia di RPM, refrigerante, tensione centralina, MAP e pressione barometrica quando supportati. Mostra solo avvisi di plausibilità per refrigerante oltre 115 °C e tensione fuori 11–16 V con motore avviato; non effettua diagnosi meccanica né cancella codici. **Copia risultato scansione** prepara il report da condividere. Questi servizi standard possono non esporre errori ABS, airbag o sensori proprietari Alfa/Fiat. Riferimento protocollo: [datasheet ELM327](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf).

**Identifica centralina motore** legge informazioni standard Mode 09: bitmap servizi, ID calibrazione, CVN e nome ECU, se disponibili. Conserva anche le risposte grezze con header ECU e protocollo dell'adattatore. **Copia identificazione ECU** prepara il report da condividere per scegliere un profilo PID esteso; non richiede Windows e non interroga ancora i PID proprietari. Non legge il VIN. Il report viene inviato al backend su Wi-Fi quando sono configurati URL HTTPS e token; la pagina **Flotta → Identificazione ECU** mostra risultato e risposta grezza. Una ECU può non indicare esplicitamente la famiglia Bosch: in quel caso servono i suoi ID e i dati grezzi per la verifica.

## OBD-II sulla Giulietta 2020 1.6 JTDm2

Associare prima l'adattatore ELM327 nelle impostazioni Bluetooth Android; poi selezionarlo dalla schermata app. L'app scopre i PID Mode 01 dichiarati dalla centralina e raccoglie quelli decodificati: giri, velocità, carico, pressioni, temperature, carburante, tensione e coppia quando disponibili. Calcola pressione relativa nel collettore da MAP e pressione barometrica, e stima coppia in Nm da percentuale effettiva e coppia di riferimento. Un PID non supportato resta assente, non assume valore zero. Se l'adattatore cade, GPS e batteria continuano. Primo controllo con telefono e auto è ancora da fare: profilo Bluetooth SPP, supporto PID e frequenza effettiva dipendono dal dongle e dalla centralina. Per parametri Alfa/Fiat specifici vedi [copertura OBD](docs/obd-coverage.md).

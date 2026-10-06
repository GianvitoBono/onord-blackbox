# Deploy sul server

`compose.server.yaml` avvia TimescaleDB/PostGIS, API Rust e dashboard React servita da Caddy. Solo porte 80/443 sono pubbliche. Database e API restano sulla rete privata Docker. Caddy gestisce HTTPS automatico per un dominio pubblico: DNS del dominio deve puntare al server e porte 80/443 devono essere raggiungibili. Le credenziali dashboard e device restano in un volume privato; dati PostgreSQL e certificati hanno volumi separati.

Se il server usa già Nginx per HTTPS, usare invece [compose.nginx.yaml e blocco Nginx](deploy-nginx.md), evitando conflitti sulle porte 80/443.

## Primo avvio

Sul server Linux con Docker Engine e Compose plugin:

```sh
cp .env.server.example .env.server
chmod 600 .env.server
```

Modificare `.env.server`: dominio vero, email ACME e password PostgreSQL casuale **esadecimale** (per esempio generata con `openssl rand -hex 32`). Non usare password dell'esempio. Il formato esadecimale evita caratteri da codificare nel `DATABASE_URL` del container.

```sh
docker compose --env-file .env.server -f compose.server.yaml up -d --build
```

Il backend applica automaticamente le migrazioni SQL prima di servire richieste. Verificare stato con:

```sh
docker compose --env-file .env.server -f compose.server.yaml ps
```

## Login dashboard e dispositivo

Dopo primo avvio, creare account dashboard e identità del veicolo/telefono:

```sh
docker compose --env-file .env.server -f compose.server.yaml run --rm backend provision_dashboard
docker compose --env-file .env.server -f compose.server.yaml run --rm backend provision_device
```

Leggere le credenziali solo sul server:

```sh
docker compose --env-file .env.server -f compose.server.yaml exec -T backend cat /var/lib/blackbox/dashboard-login.txt
docker compose --env-file .env.server -f compose.server.yaml exec -T backend cat /var/lib/blackbox/device-credentials.json
```

Per ruotare il token dispositivo: aprire dashboard → **Blackbox devices**, scegliere token generato o personalizzato, confermare con la password e copiare il token mostrato una sola volta nell'app Android. Il token personalizzato richiede 16–128 caratteri ASCII fra lettere, numeri, `_` e `-`; scegliere un valore difficile da indovinare. Quello vecchio viene invalidato. La dashboard aggiorna anche il JSON delle credenziali se appartiene al dispositivo. In alternativa, usare `docker compose --env-file .env.server -f compose.server.yaml run --rm backend provision_device --rotate`, poi leggere il JSON aggiornato.

Per vedere perché l'ingest restituisce 401: `docker compose --env-file .env.server -f compose.server.yaml logs -f --tail=100 backend`. I record JSON `ingest_auth_failed` distinguono `missing_bearer`, `unknown_device_id`, `device_revoked` e `token_mismatch`, senza riportare il token.

La sezione **Failed device connections** della dashboard conserva fino a 200 ID rifiutati negli ultimi sette giorni. Un ID `unknown_device_id` può essere approvato dopo verifica sull'app Nord, scelta del veicolo e conferma della password dashboard. Viene autorizzato l'hash del token dell'ultima richiesta rifiutata, senza conservarne il valore in chiaro. Un `token_mismatch` su dispositivo registrato richiede invece rotazione manuale del token.

Dashboard: `https://<DOMAIN>/`. Nell'app Android: backend URL `https://<DOMAIN>`, `deviceId` e `deviceToken` dal JSON. Le credenziali dashboard non vanno nell'app Android. Per ruotare password dashboard, eseguire `provision_dashboard --reset`; sessioni esistenti vengono revocate.

## Dati e aggiornamenti

Volumi Compose: `timescaledb_data` (dati), `dashboard_secrets` (credenziali), `caddy_data` e `caddy_config` (TLS). Salvare backup PostgreSQL e credenziali in posizione sicura prima di aggiornare. Esempio backup logico:

```sh
docker compose --env-file .env.server -f compose.server.yaml exec -T timescaledb \
  pg_dump -U blackbox -d blackbox -Fc > blackbox.dump
```

Aggiornamento: recuperare nuovo codice, poi rieseguire `docker compose --env-file .env.server -f compose.server.yaml up -d --build`. Non usare `down -v`: elimina i volumi. La password del database in `.env.server` deve restare uguale ai dati già inizializzati; cambiarla richiede rotazione PostgreSQL esplicita.

La mappa usa per default le tile OpenStreetMap nel browser con attribuzione visibile. Per cambiare provider impostare `VITE_MAP_TILE_URL` e `VITE_MAP_ATTRIBUTION` in `.env.server` e ricostruire `web`.

L'endpoint login non ha ancora rate limiting. Prima di esporre il dominio a Internet, aggiungere protezione al login (per esempio limite richieste sul reverse proxy) e predisporre backup automatici. Non esporre direttamente la porta PostgreSQL.

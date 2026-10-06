# Compose completo in locale, senza HTTPS

`compose.local.yaml` avvia TimescaleDB/PostGIS, API Rust e dashboard Caddy su **http://localhost:8088**. Porta pubblicata solo su `127.0.0.1`: browser del Mac può accedere, altri dispositivi di rete no. Volumi distinti da `compose.server.yaml` e `compose.yaml`; non modifica database di sviluppo attuale. Nessun dominio o certificato richiesto.

```sh
cp .env.local.example .env.local
chmod 600 .env.local
```

Sostituire `POSTGRES_PASSWORD` con una password esadecimale casuale (`openssl rand -hex 32`). Poi:

```sh
docker compose --env-file .env.local -f compose.local.yaml up -d --build
docker compose --env-file .env.local -f compose.local.yaml run --rm backend provision_dashboard
docker compose --env-file .env.local -f compose.local.yaml run --rm backend provision_device
```

Credenziali salvate nel volume `local_dashboard_secrets`:

```sh
docker compose --env-file .env.local -f compose.local.yaml exec -T backend cat /var/lib/blackbox/dashboard-login.txt
docker compose --env-file .env.local -f compose.local.yaml exec -T backend cat /var/lib/blackbox/device-credentials.json
```

Aprire `http://localhost:8088`. Per fermare: `docker compose --env-file .env.local -f compose.local.yaml down`; volumi persistono. Non usare `down -v` se servono dati. API telefonica non raggiungibile da questo Compose locale: binding loopback e HTTP sono pensati solo per browser sul Mac. Per sync dal Nord usare deploy HTTPS o tunnel HTTPS verso API.

`DASHBOARD_LOCAL_HTTP_CONTAINER=true` consente cookie senza flag Secure quando backend ascolta rete privata Docker. Usato solo qui con porta Caddy vincolata a loopback. Non copiare questo override nel Compose server.

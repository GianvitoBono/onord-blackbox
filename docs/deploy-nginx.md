# Deploy con Nginx già presente sul server

Questa variante presume Nginx installato **sull'host Linux** e un sottodominio dedicato, per esempio `blackbox.example.com`, con certificato TLS già configurato. Il browser e il Nord parlano HTTPS con Nginx; fra Nginx e Caddy interno si usa HTTP su `127.0.0.1:18088`. Caddy serve dashboard e inoltra `/api/*` al backend Rust. PostgreSQL e backend non pubblicano porte. Cookie dashboard resta `Secure=true`.

## Compose

```sh
cp .env.nginx.example .env.nginx
chmod 600 .env.nginx
```

Impostare `POSTGRES_PASSWORD` casuale esadecimale. Se si passa dal precedente `compose.server.yaml` con dati esistenti, usare **la stessa** password PostgreSQL e fermare vecchi container con `docker compose --env-file .env.server -f compose.server.yaml down` (senza `-v`). I volumi del database e delle credenziali hanno lo stesso nome progetto e vengono riutilizzati.

```sh
docker compose --env-file .env.nginx -f compose.nginx.yaml up -d --build
```

Se database nuovo, creare login e dispositivo:

```sh
docker compose --env-file .env.nginx -f compose.nginx.yaml run --rm backend provision_dashboard
docker compose --env-file .env.nginx -f compose.nginx.yaml run --rm backend provision_device
```

Per sostituire il token dispositivo, aprire la dashboard, sezione **Blackbox devices**, scegliere fra token generato (20 caratteri base32, raggruppati in quattro blocchi) e token personalizzato, inserire la password dell'account e scegliere **Rotate token**. Il token personalizzato deve avere 16–128 caratteri ASCII fra lettere, numeri, `_` e `-`, senza spazi. Usare un valore difficile da indovinare. Copiare il token mostrato una sola volta nell'app Nord. La rotazione aggiorna anche il JSON delle credenziali quando il file appartiene a quel dispositivo.

In alternativa, dopo aver aggiornato l'immagine backend, usare la CLI:

```sh
docker compose --env-file .env.nginx -f compose.nginx.yaml up -d --build
docker compose --env-file .env.nginx -f compose.nginx.yaml run --rm backend provision_device --rotate
docker compose --env-file .env.nginx -f compose.nginx.yaml exec -T backend cat /var/lib/blackbox/device-credentials.json
```

Il comando controlla che il token del file corrisponda a quello attivo nel database, aggiorna hash e file credenziali, mantiene `deviceId`/`vehicleId`. Token precedente smette subito di funzionare: inserire il nuovo `deviceToken` nell'app Nord. Non modificare JSON manualmente.

Per diagnosticare 401 dell'app Android:

```sh
docker compose --env-file .env.nginx -f compose.nginx.yaml logs -f --tail=100 backend
```

I log JSON `ingest_auth_failed` riportano `deviceId`, `batchId` e motivo: `missing_bearer`, `unknown_device_id`, `device_revoked` o `token_mismatch`. Non riportano token né header Authorization. Se `unknown_device_id` continua dopo la modifica dell'ID nell'app, aggiornare anche l'app Android: le versioni precedenti mantenevano il vecchio ID nei batch in coda.

La dashboard mostra gli ID rifiutati negli ultimi sette giorni in **Failed device connections**. Per `unknown_device_id`, confrontare l'ID con quello nell'app Nord, scegliere il veicolo, dare un nome al dispositivo e confermare con la password dashboard. L'approvazione registra l'hash del token visto nell'ultima richiesta rifiutata: non occorre copiarlo sul server. Non approvare ID sconosciuti. `token_mismatch` indica un dispositivo già registrato e richiede la rotazione manuale del token. La lista conserva al massimo 200 ID recenti e non memorizza token in chiaro.

## Blocco Nginx

Dentro il `server { listen 443 ssl; server_name blackbox.example.com; ... }` già esistente, aggiungere [nginx-location.conf](../deploy/nginx-location.conf). Se il sito contiene altri `location`, usare un sottodominio dedicato: questa regola inoltra l'intero sito, mantenendo stesso origin per dashboard e API.

```nginx
client_max_body_size 3m;
location / {
    proxy_pass http://127.0.0.1:18088;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
}
```

`proxy_pass` senza URI finale conserva `/api/v1/...`. Se `NGINX_UPSTREAM_PORT` cambia, aggiornare anche porta in Nginx. Non inserire `DASHBOARD_LOCAL_HTTP_CONTAINER=true`: quella opzione appartiene solo al Compose HTTP locale.

Prima ricaricare configurazione Nginx con `nginx -t` e il metodo di reload del proprio server. Poi aprire `https://blackbox.example.com/`; nell'app Nord usare come backend URL `https://blackbox.example.com`. Il token dispositivo resta distinto dal login dashboard.

Se Nginx gira in **un altro container**, `127.0.0.1` indica quel container: collegarlo alla rete Docker del progetto e usare `proxy_pass http://web:80;` oppure pubblicare un upstream raggiungibile dal suo network. La configurazione qui assume Nginx sull'host.

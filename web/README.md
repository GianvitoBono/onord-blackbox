# Dashboard Opnord

Webapp React + TypeScript + Vite per consultare flotte, viaggi, GPS e telemetria OBD.

## Sviluppo locale

Eseguire `npm install` e `npm run dev` dalla cartella `web`. Vite inoltra `/api` a `http://127.0.0.1:8080`. In produzione servire frontend e API dallo stesso origin; `VITE_API_BASE_URL` permette un origin API separato. Accesso con username e password del file `.data/dashboard-login.txt` nella radice del progetto. La sessione usa cookie HttpOnly.

## Interfaccia

Menu laterale: riepilogo, viaggi, mappa e telemetria, mezzi, amministrazione, impostazioni. Il mezzo selezionato resta salvato nel browser. La mappa carica i singoli punti GPS di un tour, consente di ispezionarli con il mouse e mostra le letture OBD temporalmente vicine. Il grafico permette ispezione, selezione e zoom temporale. I temi chiaro e scuro seguono inizialmente il sistema; la scelta nelle impostazioni resta salvata nel browser.

## Mappa

Predefinita: tile OpenStreetMap con attribuzione visibile. Per un provider diverso impostare `VITE_MAP_TILE_URL` e `VITE_MAP_ATTRIBUTION` durante la build. Le variabili Vite sono incorporate nel bundle: dopo una modifica serve ricostruire l'immagine web. Usare un provider con licenza e limiti adatti al traffico del proprio server.

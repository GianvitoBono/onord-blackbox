# Dashboard Opnord

Webapp React + TypeScript + Vite per consultare flotte, viaggi, GPS e telemetria OBD.

## Sviluppo locale

Eseguire `npm install` e `npm run dev` dalla cartella `web`. Vite inoltra `/api` a `http://127.0.0.1:8080`. In produzione servire frontend e API dallo stesso origin; `VITE_API_BASE_URL` permette un origin API separato. Accesso con username e password del file `.data/dashboard-login.txt` nella radice del progetto. La sessione usa cookie HttpOnly.

## Interfaccia

Menu laterale: riepilogo, viaggi, mappa Esplora, pagina Metriche, Diagnostica, mezzi, amministrazione, impostazioni. Il mezzo selezionato resta salvato nel browser. Esplora riempie il pannello con la traccia GPS e le frecce di direzione; il clic sulla linea apre misure, segnali vicini e grafico in una finestra. Metriche mostra più grafici per lo stesso viaggio, con intervallo temporale e cursore condivisi; si possono aggiungere e rimuovere segnali, trascinare per zoom e scegliere 15 minuti, 1 ora o tutto il viaggio. Ogni grafico legge al massimo 5.000 campioni e segnala quando raggiunge il limite. Le viste Esplora, Metriche e Diagnostica aggiornano i dati ogni 30 secondi. Diagnostica mostra MIL, codici guasto per categoria e ECU, storico delle scansioni e report grezzo. I temi chiaro e scuro seguono inizialmente il sistema; la scelta nelle impostazioni resta salvata nel browser.

## Mappa

Predefinita: tile OpenStreetMap con attribuzione visibile. Per un provider diverso impostare `VITE_MAP_TILE_URL` e `VITE_MAP_ATTRIBUTION` durante la build. Le variabili Vite sono incorporate nel bundle: dopo una modifica serve ricostruire l'immagine web. Usare un provider con licenza e limiti adatti al traffico del proprio server.

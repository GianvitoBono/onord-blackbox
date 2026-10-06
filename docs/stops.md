# Riconoscimento soste

Il telefono registra `stop_start` dopo 10 secondi senza spostamento significativo, mantenendo lo stesso `stopId` fino a `stop_end`. Conferma la sosta con GPS e velocità OBD `010D` quando recente; il pedale acceleratore `0149` aiuta a confermare la ripartenza rispetto al valore a riposo, senza usarlo come soglia assoluta. Se l'OBD manca o è vecchio, usa solo GPS. Una singola posizione GPS fuori posto non termina la sosta: il rilevatore richiede avanzamento su una seconda posizione. Alimentazione e stato Bluetooth non decidono i confini del viaggio; una sosta normale resta nello stesso viaggio. Il viaggio termina dopo 90 minuti fermo o quando l'utente ferma il monitor.

Gli eventi registrati dall'app sono autoritativi e vengono sincronizzati al backend. In Esplora la linea del percorso mostra marker distinti per inizio e fine sosta, elenco delle durate e dettaglio al clic. Per viaggi precedenti privi di eventi, il frontend prova una stima: almeno 10 secondi e 3 punti entro 25 m, precisione GPS al massimo 20 m, poi partenza di almeno 65 m. Le soste stimate sono etichettate; non vengono scritte nel database.

I due viaggi presenti il 6 ottobre 2026 non contengono eventi sosta registrati dalla vecchia app. Con soglia di 10 secondi il frontend può stimare fermate in quei viaggi; la stima non equivale a un evento registrato dall'app con conferma OBD.

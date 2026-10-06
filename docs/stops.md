# Riconoscimento soste

Il telefono registra `stop_start` dopo 2 minuti senza spostamento significativo, mantenendo lo stesso `stopId` fino a `stop_end`. Una singola posizione GPS fuori posto non termina più la sosta: il rilevatore richiede avanzamento su una seconda posizione. Alimentazione e stato Bluetooth non decidono i confini del viaggio; una sosta normale resta nello stesso viaggio. Il viaggio termina dopo 90 minuti fermo o quando l'utente ferma il monitor.

Gli eventi registrati dall'app sono autoritativi e vengono sincronizzati al backend. In Esplora la linea del percorso mostra marker distinti per inizio e fine sosta, elenco delle durate e dettaglio al clic. Per viaggi precedenti privi di eventi, il frontend prova una stima: almeno 2 minuti e 3 punti entro 40 m, precisione GPS al massimo 35 m, poi partenza di almeno 65 m. Le soste stimate sono etichettate; non vengono scritte nel database.

I due viaggi presenti il 6 ottobre 2026 non contengono eventi sosta; la sequenza più lunga con velocità GPS sotto 1 m/s dura rispettivamente circa 56 e 90 secondi. Non è corretto aggiungere marker di sosta su quei viaggi con la soglia attuale.

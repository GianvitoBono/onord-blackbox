# Metriche, campioni e query geospaziali

## Modello

`metric_definitions` è il catalogo: nome stabile, origine, unità canonica e tipo. `metric_series` è la testa della serie: metrica + device + veicolo + tour + etichette controllate. `metric_samples` contiene tempo, valore e ID del campione; è una hypertable TimescaleDB. Esempio: `obd.pid.010c` → serie della Giulietta/telefono/tour → letture RPM. Le dimensioni ad alta cardinalità (timestamp, posizione, sample ID) non sono etichette.

`vehicles`, `devices`, `device_vehicle_assignments`, `trips` e `ingest_batches` rimangono tabelle relazionali. Un nuovo assegnamento del telefono non cambia l'identità delle serie storiche: veicolo e tour sono salvati nella testa al momento dell'ingest.

`gps_samples` conserva fix e qualità come righe specializzate, collegate a `trips` da `trip_id`. `position` è `geography(Point,4326)` derivata da longitudine/latitudine e indicizzata GiST. `delta_sec` e `delta_m` misurano rispettivamente tempo e distanza geodetica dal fix precedente dello stesso tour; primo punto: entrambi `NULL`. Ordinamento stabile `(observed_at, sample_id)`. I batch fuori ordine ricalcolano punti inseriti e successore nella stessa transazione; `trips.distance_gps_m` somma i delta. Si tratta di distanza fra fix, non percorso stradale. Le metriche GPS numeriche (velocità, quota, precisione) sono anche campioni di serie; nessun punto viene copiato su ogni lettura OBD. Le letture OBD e GPS hanno orologi/cadenze distinti: la correlazione usa finestra temporale esplicita, senza inventare una posizione per ogni PID.

Migrazione `0004_gps_deltas.sql`: aggiunge colonne nullable e backfill senza perdere coordinate o ID. Rollback applicativo possibile con reader/writer precedenti: colonne aggiuntive sono ignorate. Non eliminare le colonne nella stessa fase; eventuale rimozione richiede migrazione distinta.

## Query spazio-tempo

Esempio: ultimi valori RPM raccolti entro 500 m da un punto, con tolleranza temporale di 2 s. Parametri `$1`/`$2`: inizio/fine; `$3`/`$4`: longitudine/latitudine.

```sql
WITH fixes AS (
  SELECT trip_id, observed_at, position
  FROM gps_samples
  WHERE observed_at >= $1 AND observed_at < $2
    AND ST_DWithin(position,
      ST_SetSRID(ST_MakePoint($3, $4), 4326)::geography, 500)
  ORDER BY observed_at
  LIMIT 1000
)
SELECT f.observed_at AS gps_at, ST_Y(f.position::geometry) AS latitude,
       ST_X(f.position::geometry) AS longitude,
       m.observed_at AS metric_at, m.value_numeric AS rpm
FROM fixes f
JOIN metric_series s ON s.trip_id = f.trip_id
JOIN metric_definitions d ON d.id = s.metric_id AND d.name = 'obd.pid.010c'
JOIN LATERAL (
  SELECT observed_at, value_numeric FROM metric_samples
  WHERE series_id = s.id
    AND observed_at BETWEEN f.observed_at - interval '2 seconds'
                        AND f.observed_at + interval '2 seconds'
  ORDER BY abs(extract(epoch FROM observed_at - f.observed_at))
  LIMIT 1
) m ON true;
```

Per heatmap o traffico su strada servirà un passaggio distinto di map matching e una fonte di traffico esterna. Modello previsto: `road_segments(id, geom LineString, ...)`, `trip_segment_matches(trip_id, gps_at, segment_id, confidence)` e `traffic_observations(segment_id, observed_at, source, speed_kmh, congestion)`. La correlazione usa segmento + intervallo temporale + tour; `source` e `confidence` distinguono dato osservato da stima. I fix GPS indicano il percorso rilevato; non identificano da soli corsia, arco stradale o stato del traffico. Future viste aggregate possono usare `time_bucket` e distanza GPS, mantenendo separati dati osservati e stimati.

## Transizione

Migrazione `0002_metric_geo.sql` aggiunge oggetti e indice geografico; non elimina tabelle né modifica JSON v1. Il backend scrive vecchie tabelle e nuove serie nella stessa transazione, poi emette ACK. Rollback applicativo: tornare al reader/writer v1; le vecchie tabelle restano fonte completa. Dati già presenti prima della migrazione restano nelle vecchie tabelle; per portarli nelle serie servirà un backfill per intervalli temporali con controllo dei conteggi. Eliminazione delle tabelle storiche richiede fase separata e backup verificato.

La variante Docker `timescale/timescaledb-ha:pg17.11-ts2.30.0` include PostGIS. Directory nuova `.data/postgres-postgis` sul disco esterno; `.data/postgres` precedente resta conservata. Il tag HA occupa più spazio del precedente Alpine: scelta necessaria per PostGIS preinstallato e compatibilità riproducibile.

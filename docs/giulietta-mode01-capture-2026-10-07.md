# Acquisizione Mode 01 della Giulietta — 7 ottobre 2026

Fonte: JSON `read_only_mode_01_capture` fornito dall'utente, acquisito alle 06:41 UTC con adattatore che dichiara `ELM327 v1.5`. `ATDPN=A0` fu letto prima del primo comando auto-rilevato (`0100`); non identifica il protocollo effettivo della sessione. Le risposte hanno ID CAN a 29 bit `18DAF101` e `18DAF110`.

## Bitmap per rispondente

I bit di continuazione (`20`, `40`, `60`, `80`, `A0`) indicano la bitmap successiva e non sono misure. Qui compaiono i PID di dato dichiarati dalle risposte complete.

| Rispondente | PID di dato dichiarati |
| --- | --- |
| `18DAF101` | `01`, `04`, `05`, `0C`, `0D`, `1C`, `21`, `33`, `46`, `85`, `9B` |
| `18DAF110` | `01`, `04`, `05`, `0B`, `0C`, `0D`, `10`, `13`, `1C`, `1F`, `21`, `2F`, `30`, `31`, `33`, `34`, `42`, `45`, `46`, `49`, `4A`, `4C`, `4F`, `62`, `63`, `68`, `69`, `6B`, `6D`, `71`, `78`, `7A`, `83`, `85`, `88`, `8B`, `8E`, `8F`, `92`, `9D`, `A1` |

`18DAF110` è candidato pratico per le misure motore/emissioni: dichiara il set diesel ampio. Non assegna da solo nome o famiglia alla ECU. Le bitmap non provano che ogni richiesta restituirà una misura valida.

## Risposte utilizzabili

- `0101` completo da entrambe le sorgenti: monitor/DTC codificati, non numero continuo. Il primo byte `00` indica MIL spenta e zero DTC dichiarati in quell'istante.
- `0134` completo da `18DAF110`: `2A9F7FFF`, quattro byte. Con le formule standard: rapporto di equivalenza `0x2A9F / 32768 ≈ 0,333`; corrente `0x7FFF / 256 − 128 ≈ −0,0039 mA`. Valore puntuale da confrontare con stato motore e altra acquisizione.
- `014F` completo: `06000028`. Sono limiti/capacità dichiarati, non valori live.
- `018E` completo: `87` esadecimale = 135; attrito motore `135 − 125 = 10%` della coppia di riferimento.
- `0192` (`0900`) e `019D` (`00000000`) sono completi come trasporto, ma composti: mantenere byte e struttura, non rappresentarli come singola misura senza decodifica verificata.

## Risposte tronche

`0168`, `0169`, `016D`, `0171`, `0178`, `017A`, `0183`, `0185`, `0188`, `018B`, `018F`, `01A1` mostrano un First Frame ISO-TP (`10 xx`) senza Consecutive Frame (`21`, ecc.). Per esempio `0171` annuncia otto byte di payload ma fornisce solo i primi sei. `ATCFC1=OK` dimostra solo che l'adattatore ha accettato il comando: non dimostra che abbia inviato correttamente il Flow Control. Senza i frame mancanti non si possono assegnare valori fisici ai sottocampi. La stessa anomalia appariva nelle risposte Mode 09.

La precedente conversione in `raw_unsigned_integer` prendeva i quattro byte successivi a `41 PID` anche da un First Frame incompleto. Gli interi giganti nei grafici possono quindi essere **frammenti**, non valori della ECU. Da app 0.1.12 la telemetria numerica ignora questi frammenti, conserva la risposta integrale come diagnostica grezza, unisce le bitmap di entrambe le sorgenti e legge i PID condivisi da `18DAF110` quando disponibile. Le serie storiche restano da trattare come codificate, non come misure.

Per ottenere valori DPF/EGR/VGT/NOx servono risposte ISO-TP complete. Ripetere la cattura con un adattatore che gestisca correttamente flow control CAN 29 bit; prima di spendere, la prossima acquisizione dovrebbe riportare `ATDPN_after_0100`, così il protocollo selezionato è verificabile. [Datasheet ELM327](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf): First Frame, Flow Control e Consecutive Frame. Nomi dei PID: [tabella tecnica Mode 01](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979).

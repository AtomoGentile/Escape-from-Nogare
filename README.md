# NOGAR // HUNT

Prima versione Android di nascondino geografico: Android 9+ (API 28), interfaccia arcade, mappa reale OpenStreetMap con Leaflet 1.9.4 incluso. Nessuna chiave Maps necessaria. Non usa Google Maps né geocoding. Le tessere richiedono Internet: nessun download offline o prefetch. Attribuzione sempre visibile; per uso pubblico ad alto traffico scegliere un provider di tile con contratto adeguato.

## Come giocare

1. Ospitare il server su un indirizzo **HTTPS** e inserirlo nell'app su tutti i telefoni.
2. Scegliere nickname e squadra; creare una lobby o entrare col codice a sei cifre.
3. Il creatore sceglie durata, diametro iniziale, intervallo ping e velocità di ritiro del **raggio** in m/s. Servono almeno una preda e un cacciatore per iniziare.
4. Consentire GPS preciso e notifiche. Dopo l’ingresso il servizio Android mantiene GPS e ping attivi anche con schermo spento o app in background. Il creatore avvia con un fix recente entro 100 m di precisione: quel punto diventa il centro fisso del campo.
5. Tutti vedono la propria posizione continua e l'ultimo ping di tutti gli altri, con squadra, precisione ed età. Il server limita i ping durante la partita; la prima posizione viene pubblicata subito. In attesa lobby le posizioni si aggiornano ogni circa 2 secondi.
6. Il campo si riduce fino a un raggio minimo di 25 m; alla scadenza termina il timer. Il creatore può terminare la partita per tutti; termina anche se una squadra lascia la lobby. Le coordinate vengono eliminate alla fine. Catture e vittoria sono concordate di persona: non ci sono eliminazioni automatiche o rilevamento contatto.

| Preset | Durata | Diametro | Ping | Ritiro raggio |
|---|---:|---:|---:|---:|
| Sprint | 10 min | 600 m | 15 s | 0,4 m/s |
| Classic | 20 min | 1200 m | 30 s | 0,4 m/s |
| Explorer | 30 min | 2000 m | 45 s | 0,5 m/s |

Indicazioni da adattare al luogo, non valutazioni di sicurezza. Il GPS non garantisce precisione assoluta. Il cerchio ignora strade e ostacoli: usare solo luoghi autorizzati e percorsi sicuri.

## Server

Node.js 22 o successivo, senza dipendenze esterne:

```sh
cd /workspace/Escape-from-Nogar-/server
npm test
npm start
```

Ascolta su `127.0.0.1:8080`, configurabile con `HOST` e `PORT`. Verifica `GET /health`. Per dispositivi reali serve un dominio HTTPS raggiungibile con reverse proxy (es. Caddy: `reverse_proxy 127.0.0.1:8080`) e certificato valido. Il certificato TLS non viene disabilitato. Non viene pubblicato automaticamente alcun servizio.

Distribuzione: copiare `server/` su una VM con Node, eseguire tramite supervisore (systemd), esporre solo il reverse proxy HTTPS. Non registrare i body delle richieste: contengono token e coordinate. Una singola istanza conserva le lobby in RAM; riavviarla perde le partite. Massimo 50 utenti per lobby e 1000 lobby, scadenza dopo 6 ore di inattività. Nessun account o storico. Il codice consente l'ingresso prima dell'avvio: condividerlo solo coi partecipanti. I token casuali restano nella memoria del servizio Android. Tornando all’app si ripristina la lobby finché il servizio è vivo; se Android termina il processo occorre un nuovo ingresso, non consentito a partita iniziata. La versione attuale non include reconnect persistente, espulsione, anti-cheat o protezione DDoS distribuita.

## Android

JDK 17 o 21, Gradle 8.11.1, SDK Android 35 e Build Tools 35.0.0. Impostare `ANDROID_HOME` oppure `sdk.dir` in `local.properties` (ignorato da Git).

```sh
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

La build debug è installabile per prove; per distribuzione pubblica occorrono firma release, verifica delle policy store e collaudo su dispositivi Android 9 e recenti. Il progetto non include credenziali di firma. Leaflet è distribuito con licenza BSD-2-Clause in `assets/LEAFLET-LICENSE`. Le immagini della mappa arrivano via HTTPS da `tile.openstreetmap.org` (policy: https://operations.osmfoundation.org/policies/tiles/).

## Collaudo sul campo necessario

Due telefoni reali: creare/entrare, autorizzare GPS, verificare ping di entrambe le squadre, avviare, controllare cerchio e timer, interrompere rete, mettere in background e uscire. Nessuna prova in emulatore sostituisce questo controllo. V2 iperrealistica non implementata.

## GitHub Actions

Il workflow `.github/workflows/android.yml` compila a ogni push/PR e può partire manualmente da **Actions → Android APK → Run workflow**. Esegue 12 test server/interfaccia e lint, verifica il limite di 100 MB e pubblica `nogar-android-debug` negli artefatti della run. Il workflow viene eseguito solo dopo aver pubblicato questi file nel repository; la sua presenza locale non equivale a una run completata.

### Stato delle verifiche locali

12 test automatici: 6 test del server con tempo simulato (ping, ruoli, scadenza, autorizzazioni, uscita, coordinate e limiti) e 6 test DOM dell’interfaccia (form, nomi sicuri, timer, GPS delegato e uscita). Eseguirli con `npm ci --ignore-scripts && npm test` dalla radice. I test DOM simulano la mappa e il bridge Android: non verificano grafica, GPS fisico o ciclo di vita Android. Il layout degli asset è stato verificato in Chromium a 390 × 844 servendoli via HTTP locale: ingresso e lobby non hanno errori JavaScript o overflow orizzontale. Questo controllo non sostituisce il collaudo WebView/Android e non verifica GPS o disponibilità delle tessere mappa. Nessun telefono o emulatore Android è stato usato.


## GPS in background (v0.3)

`TrackingService` usa un foreground service Android di tipo `location`, una notifica persistente con **ESCI E FERMA GPS**, un ciclo di rete nativo indipendente dai timer WebView e un wake lock limitato. Parte solo entrando in una lobby con app visibile e permesso GPS preciso. Non richiede il permesso “posizione sempre” perché il servizio viene avviato mentre l’app è visibile. Su Android 13+ richiede anche le notifiche: se negate Android può nascondere la notifica dal cassetto, pur mostrando il servizio nel gestore delle app attive.

Esci dalla lobby o usa l’azione nella notifica per fermare il GPS. Si ferma anche a fine partita e dopo massimo 130 minuti di sessione, compresa l’attesa in lobby. L’uscita dal server è tentata anche se manca rete, ma non può essere garantita finché la rete non torna. Un arresto forzato, un riavvio o le restrizioni batteria del produttore possono interrompere il servizio: non esiste garanzia universale a schermo spento e non viene richiesto di disattivare indiscriminatamente l’ottimizzazione batteria.

## Revisione v0.3

- Identità con l’icona fornita (resa vettoriale e adattiva), schermate di ingresso, preset e dashboard ridisegnate; mappa chiara con legenda e pulsanti ricentra/zona.
- Copia codice, cambio squadra prima dell’avvio, chiusura host, conferma uscita, squadra e stato online dei partecipanti.
- Timer e cerchio interpolati usando un orologio monotono: continuano a scorrere tra le risposte. Segnalazioni esplicite per GPS e connessione obsoleti.
- Ping condivisi con scadenza iniziale comune; nomi mostrati come testo, risposte di vecchie lobby ignorate; mappa ripristinata tra sessioni.
- Limiti richieste per giocatore invece di una quota troppo bassa condivisa dal reverse proxy; pulizia periodica delle lobby inattive e cancellazione coordinate a fine partita.

Il servizio server deve comunque essere ospitato su HTTPS per giocare fra telefoni: GitHub Actions compila l’APK ma non ospita le lobby.


## v0.4 · Mockup e build riproducibile

Interfaccia riallineata al concept approvato: font Anton incluso (SIL OFL, `assets/ANTON-LICENSE`), radar circolare, profilo modificabile e salvato localmente senza token, icone vettoriali di prede/cacciatori, preset con segno di selezione, impostazioni campo, dashboard e schede giocatore. La mappa resta geografica e chiara.

Gradle Wrapper 8.11.1 è incluso con checksum SHA-256 della distribuzione. Actions installa esplicitamente piattaforma Android 35 e Build Tools 35.0.0, separa test/build/lint/verifica APK e conserva APK e report anche se un controllo successivo fallisce. Non richiede segreti per la build debug. Verificati localmente Node 22, wrapper con cache vuota, build e lint. L’esito della run remota non è verificabile finché l’ambiente non può leggere `api.github.com`; serve il log del passaggio fallito per attribuire la precedente segnalazione a una causa specifica.

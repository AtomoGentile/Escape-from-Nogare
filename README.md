# NOGAR // HUNT · Android 9+ · v0.8

Nascondino geografico con lobby, prede/cacciatori, ping su mappa e zona che si restringe. **Dalla v0.7 il telefono organizzatore gestisce la partita.** Il servizio esterno è solo una casella di collegamento e inoltro di messaggi cifrati: non esegue le regole e non riceve la chiave dell’invito.

## Collegamento con Google, senza PC

La v0.8 aggiunge Firebase Realtime Database usando un progetto nel tuo account Google. Non richiede un account di hosting diverso, un PC acceso o porte aperte. Rimane necessaria una configurazione iniziale del progetto, Auth anonimo e regole: [guida Firebase](docs/FIREBASE.md). Per il piano gratuito mantieni Spark e controlla le quote correnti nella console. Non vengono attivati servizi a pagamento.

Il telefono continua a gestire le regole e le posizioni rimangono cifrate. L'app usa due campi: URL database e chiave API **pubblica**; gli inviti completi precompilano entrambi. I giocatori non devono fare login Google. Il precedente relay Node/Windows resta disponibile come alternativa.

## Giocare

1. Collega nell’app Firebase come sopra oppure un servizio HTTPS `nogar-relay`. [Guida per ottenere l’indirizzo](docs/SERVER.md). Tutti usano la stessa versione dell’app.
2. L’organizzatore sceglie nickname, squadra e regole, poi **Crea lobby**. Il suo telefono deve rimanere acceso e connesso; GPS e connessioni sono nel servizio Android in background con notifica persistente.
3. Tocca il pulsante di copia accanto al codice: copia l’**invito privato completo** `nogar://join#…`. Condividilo solo con i partecipanti. Il codice a sei cifre identifica la lobby, ma non basta per entrare perché non contiene la chiave crittografica.
4. Gli altri aprono l’invito con Nogar oppure lo copiano e toccano **Ho un invito · Incolla**. L’app propone il servizio dell’invito senza contattarlo automaticamente. Premono Connetti, scelgono nickname e squadra ed entrano.
5. Servono una preda e un cacciatore connessi. L’organizzatore avvia con un GPS recente e preciso entro 100 m: questo punto diventa il centro fisso del campo.
6. Ognuno vede il proprio GPS continuo e l’ultimo ping degli altri. Entrambe le squadre vengono rivelate. Fuori dalla lobby, il client invia una posizione all’organizzatore solo quando è dovuto un ping, anziché spedire continuamente gli spostamenti intermedi.
7. Il creatore può terminare la partita. Alla scadenza o all’uscita di una squadra il motore chiude la partita e cancella le posizioni dei giocatori. Le catture si concordano di persona, senza rilevamento contatto automatico.

Il telefono organizzatore non viene sostituito automaticamente. Se esce, viene spento o il processo viene terminato, la partita si interrompe. Il relay cancella la casella dopo due minuti senza il telefono organizzatore o dopo tre ore; un riavvio del relay può interrompere anche la sessione corrente. I partecipanti non possono rientrare dopo l’avvio se il loro processo è stato perso.

## Regole e preset

| Preset | Durata | Diametro | Ping | Ritiro raggio | Raggio minimo |
|---|---:|---:|---:|---:|---:|
| Sprint | 10 min | 600 m | 15 s | 0,4 m/s | 25 m |
| Classic | 20 min | 1200 m | 30 s | 0,4 m/s | 25 m |
| Explorer | 30 min | 2000 m | 45 s | 0,5 m/s | 25 m |

Tutti i parametri sono personalizzabili. Il raggio minimo può variare da 1 m alla metà del diametro iniziale. Il server sul telefono e la mappa rispettano lo stesso limite. Il GPS non garantisce precisione assoluta: il cerchio è virtuale, ignora strade e ostacoli. Usare solo aree autorizzate e percorsi sicuri.

## Cosa passa dai servizi esterni

- **Diretto:** WebRTC DataChannel fra ciascun partecipante e l’organizzatore. Non vengono create tracce audio/video né richiesti microfono o fotocamera.
- **Firebase opzionale:** Auth anonimo e Realtime Database sostituiscono il relay. Le regole in `firebase/database.rules.json` limitano accessi, mittente, destinatario e formato opaco. Il progetto riceve chiave API pubblica e metadati, non la chiave AES. Nessuna funzione cloud. Quote, presenza e pulizia dei dati sono descritte nella guida; i dati cifrati rimasti dopo un arresto non vengono eliminati automaticamente dal solo passaggio del tempo.
- **Collegamento e alternativa Node:** `server/relay.mjs` trasporta la segnalazione WebRTC e, quando il diretto non risponde, gli stessi messaggi di gioco cifrati via HTTPS. Non occorre un server TURN. Il relay vede IP, identificatori, tempi, dimensioni e pacchetti cifrati; conserva code in RAM, non uno storico delle posizioni. Il servizio rimane contattato per disponibilità e segnalazione anche quando il canale di gioco è diretto.
- **Cifratura:** AES-256-GCM con nonce casuale e AAD legata a lobby, mittente e destinatario. La chiave casuale resta nell’invito sui telefoni e non viene inviata al relay. Anche SDP/candidati ICE sono cifrati. La finestra di sequenza rifiuta replay e consente riordinamento limitato. Le richieste ritentate usano lo stesso identificatore e l’organizzatore conserva risposte limitate per evitare di rieseguire i comandi.
- **STUN:** `stun.cloudflare.com:3478` aiuta a trovare il collegamento diretto. Riceve informazioni di rete, non le coordinate del gioco. Per infrastruttura propria, sostituire l’URL STUN in `PhoneSession.java`.
- **Mappa:** le tessere arrivano da OpenStreetMap via HTTPS, senza chiave Google Maps. Il fornitore vede IP e area delle tessere richieste, non i marker aggiunti localmente. Mappe offline non sono implementate. Rispettare la [policy delle tessere](https://operations.osmfoundation.org/policies/tiles/) e scegliere un provider appropriato per traffico pubblico elevato.

Chi possiede l’invito è autorizzato ad accedere alla partita. La chiave è condivisa fra i partecipanti: niente revoca individuale, segretezza futura o protezione da un partecipante che ricondivide l’invito. L’organizzatore è l’autorità fidata. Nessuna promessa di anonimato, nessun audit crittografico indipendente effettuato.

## Servizio sul proprio PC Windows 11

Per evitare account di hosting usa la [guida Windows a casa](docs/WINDOWS-HOME.md). Include script di avvio e firewall, HTTPS automatico con Caddy e nome DNS sslip.io senza registrazione. Richiede IP WAN pubblico, porte TCP 80/443 aperte verso il PC e una verifica da rete mobile. Il PC deve restare acceso; non e stato configurato da remoto.

## Avvio del solo relay

Node 22+, nessuna dipendenza runtime:

```sh
cd server
HOST=0.0.0.0 PORT=8080 npm start
```

Per gli utenti finali esporre tramite HTTPS con certificato valido. `GET /health` deve restituire `{"ok":true,"service":"nogar-relay","protocol":2}`. Il file `render.yaml` configura questo servizio sul proprio account Render. La pubblicazione non è stata effettuata dall’agente; non esiste un URL pubblico preconfigurato. Hosting e rete mobile possono imporre quote di traffico: non è garantito un uso gratuito illimitato. Il piano gratuito può dormire o riavviarsi: la disponibilità continua non è garantita.

Il precedente `server/server.mjs` rimane come riferimento/test della vecchia architettura e si avvia solo con `npm run start:legacy`. **L’app v0.7 rifiuta questo endpoint**, perché elaborerebbe le posizioni nel cloud. Se avevi già un deploy, cambia Start Command in `node relay.mjs` e ridistribuisci.

## Sviluppo e build

JDK 17, Gradle Wrapper 8.11.1, SDK Android 35, Build Tools 35.0.0, Node 22+. Impostare `ANDROID_HOME` oppure `sdk.dir` in `local.properties` ignorato da Git.

```sh
npm ci --ignore-scripts
npm test
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

WebRTC nativo è fissato a `io.github.webrtc-sdk:android:150.7871.04` (min API 21; l’app richiede API 28). Include ARM32, ARM64, x86 e x86_64, aumentando l’APK a circa 49 MB, entro il limite di 100 MB. Il build debug è per prove; la distribuzione pubblica richiede firma propria. I file di licenza Leaflet, Anton, SDK WebRTC e WebRTC nativo/PATENTS sono inclusi negli asset. R8 conserva le API WebRTC richiamate da JNI.

Actions esegue test Node, build APK, test JVM dell’autorità/cifratura/relay reale, lint e verifica firma/dimensioni; carica APK e report. La configurazione è nel repository, l’esito remoto richiede accesso ai log di GitHub.

## Stato delle verifiche e limiti

L’integrazione Firebase include test delle regole e del client REST Java con Auth/Database Emulator in Actions. La rete locale di questo ambiente blocca il download ufficiale dell’emulatore: i test vengono eseguiti sul runner GitHub e il controllo fallisce anche se la prova REST Java viene saltata. La run iniziale [41071236119](https://github.com/AtomoGentile/Escape-from-Nogare/actions/runs/41071236119) ha superato build, emulatori e lint. Non e stato configurato alcun progetto Firebase reale.


- Test Node del precedente motore, relay opaco, validazione, autorizzazioni e DOM dell’interfaccia.
- Test JVM di regole sul telefono, raggio minimo, ping, ruoli, uscita, AES-GCM, manomissione, mittente/lobby sbagliati, inviti e replay.
- Integrazione JVM con **relay Node reale**: richiesta cifrata, elaborazione sull’autorità Java e risposta cifrata. Non usa WebRTC nativo: verifica l’alternativa di inoltro e il formato crittografico, non la negoziazione ICE.
- Build e lint Android; nessun telefono/emulatore disponibile. **La negoziazione WebRTC, le reti mobili reali e il background su Android 9/recente restano da collaudare su due dispositivi.**

Il servizio parte mentre l’app è visibile con GPS preciso; su Android 13+ chiede anche le notifiche. La notifica permette di fermare GPS e condivisione. Limite di sessione 130 minuti, compresa la lobby. Restrizioni batteria, arresto forzato, riavvio o cambio rete possono interromperlo. Nessun servizio a pagamento è attivato automaticamente.

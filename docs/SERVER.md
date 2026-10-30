# Collegare i telefoni via Internet · v0.9

**Puoi scegliere un server completo sul PC oppure il telefono organizzatore con un relay cifrato.** In modalità telefono: Serve comunque un piccolo servizio HTTPS per mettere in contatto telefoni dietro CGNAT e inoltrare messaggi cifrati quando il collegamento diretto non riesce. Non è un server delle posizioni: non riceve la chiave dell’invito e non esegue regole del gioco.

## Con un account Google, senza PC

Usa la [guida Firebase](FIREBASE.md): un progetto Spark con Auth anonimo e Realtime Database puo sostituire il relay Node, mantenendo i messaggi cifrati. Servono una configurazione iniziale e il rispetto delle quote gratuite.

## Sul proprio PC Windows 11, senza account di hosting

Segui la [guida Windows a casa](WINDOWS-HOME.md): Lo script `Start-HomeGame.ps1` avvia il server completo sul PC e Caddy pubblica HTTPS. Il PC riceve le posizioni e gestisce le regole; la chiave API Firebase resta vuota. Il controllo `/health` risponde `nogar-pc`, protocollo 3. Puoi ancora scegliere `Start-HomeRelay.ps1` per il solo relay cifrato. Servono IP WAN pubblico e TCP 80/443 inoltrate al PC; DNS e certificato vengono ottenuti automaticamente senza account di hosting. Il PC deve restare acceso.

## In alternativa: ottenere l’indirizzo su Render

1. Accedi al tuo account [Render](https://render.com/) e collega il repository GitHub `AtomoGentile/Escape-from-Nogare`.
2. Usa **New → Blueprint**, branch `main`. `render.yaml` propone `nogar-relay` con piano Free. Controlla il piano prima di confermare. Non viene aperto un servizio a pagamento automaticamente.
3. Attendi **Live** e copia l’URL HTTPS assegnato nella pagina del servizio. Non esiste un URL pubblico già attivo nel progetto.
4. Apri quell’URL con `/health`: deve rispondere con `ok: true`, `service: nogar-relay`, `protocol: 2`.
5. Nell’app l’organizzatore incolla l’indirizzo di base **senza `/health` o `/api`** e preme Connetti.
6. Crea la lobby dal telefono. Con il pulsante di copia accanto al codice copia l’**invito completo** e condividilo privatamente. Contiene anche la chiave per decifrare i messaggi; non pubblicarlo.
7. Gli altri aprono l’invito con Nogar o lo copiano e usano **Ho un invito · Incolla** nella schermata iniziale. Dopo Connetti scelgono nome e squadra ed entrano.

Il codice a sei cifre rimane l’identificativo della lobby. Da solo non permette di entrare: una chiave così breve non proteggerebbe le posizioni dal gestore del servizio.

## Se usi “New → Web Service”

| Campo | Valore |
|---|---|
| Runtime | Node |
| Branch | `main` |
| Root Directory | `server` |
| Build Command | `node --check relay.mjs` |
| Start Command | `node relay.mjs` |
| Health Check Path | `/health` |
| `HOST` | `0.0.0.0` |
| `NODE_VERSION` | `22` |

Render assegna `PORT`; il programma la legge. Il certificato HTTPS viene fornito dall’hosting. Non servono chiavi Maps, chiavi di cifratura da inserire sul server o credenziali TURN. Una sola istanza: le caselle sono in memoria.

**Scelta del servizio:** `node relay.mjs` inoltra solo messaggi cifrati; `node server.mjs` gestisce tutta la partita e riceve le coordinate dopo la terminazione HTTPS. Usa il secondo sul tuo PC fidato. La v0.9 riconosce entrambi e indica la modalità.

## Costi e dati

Il piano gratuito può andare in pausa o riavviarsi: un riavvio elimina le caselle e può interrompere le partite. Al primo collegamento, attendi che `/health` risponda e riprova. Nessun pagamento è necessario per provare, ma non è garantito un servizio gratuito sempre disponibile.

Il relay vede IP, orari, identificatori e pacchetti cifrati. I telefoni restano i soli possessori della chiave, salvo chi riceve una copia dell’invito. STUN Cloudflare vede le informazioni necessarie alla connessione diretta. OpenStreetMap vede IP e richieste delle tessere, che possono rivelare l’area visualizzata. Non è una modalità senza alcun servizio esterno.

Il telefono organizzatore deve restare acceso e connesso, con il servizio GPS attivo in background. Se esce o si spegne non viene eletto un altro organizzatore. Per fermare tutto usa la notifica o Esci dalla lobby.

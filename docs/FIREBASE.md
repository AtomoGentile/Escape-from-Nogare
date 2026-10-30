# Firebase con il tuo account Google · v0.8

Questa opzione evita PC acceso, porte del router e account su un hosting differente. Firebase e un servizio da attivare usando l'account Google esistente: **non e gia configurato solo perche usi Gmail**. Chi organizza configura un progetto una volta; i partecipanti non devono accedere con il proprio account Google.

La partita resta sul telefono organizzatore. Realtime Database e una casella per messaggi cifrati; non esegue regole di gioco e non riceve la chiave dell'invito.

## 1. Progetto gratuito

1. Apri [Firebase Console](https://console.firebase.google.com/) con il tuo account Google e crea un progetto per Nogar.
2. Mantieni il piano **Spark** e disattiva Google Analytics se proposto: l'app non lo usa. Non attivare Blaze o collegare la fatturazione per questa configurazione.
3. Prima di procedere controlla nella console le [quote e i prezzi correnti](https://firebase.google.com/pricing): non promettiamo utilizzo gratuito illimitato. Al raggiungimento dei limiti il servizio puo smettere di funzionare. Non usiamo Cloud Functions o server a pagamento.

Nessun progetto e stato creato da questa chat e nessun account Google e stato collegato all'agente.

## 2. Database e accesso dei telefoni

1. In **Build → Authentication → Sign-in method** abilita **Anonymous / Anonimo**. Non occorre abilitare accesso Google, email o telefono per i giocatori.
2. In **Build → Realtime Database** crea un database. Scegli la regione appropriata e parti in **modalita bloccata**, non in modalita test/pubblica.
3. Nella scheda **Rules / Regole**, sostituisci le regole con l'intero contenuto di [firebase/database.rules.json](../firebase/database.rules.json) del repository e premi **Publish / Pubblica**.
4. Copia l'URL del database dalla console. Avra una forma come `https://nome-default-rtdb.europe-west1.firebasedatabase.app` oppure `https://nome-default-rtdb.firebaseio.com`. Sono formati di esempio, non servizi gia attivi.

Le regole impediscono letture pubbliche, richiedono la prova dell'invito per entrare nella lobby e consentono a ogni utente di leggere solo la propria casella. Un partecipante puo scrivere solo all'organizzatore; solo l'organizzatore puo inoltrare agli altri. I pacchetti accettati contengono soltanto testo cifrato e metadati di consegna.

## 3. Chiave API pubblica

In **Project settings / Impostazioni progetto → General / Generali** copia la **Web API Key / Chiave API Web**. Se non viene mostrata, registra un'app Web nel progetto (icona `</>`, senza attivare Firebase Hosting) e usa il campo `apiKey` della configurazione proposta.

Questa e la chiave pubblica di configurazione Firebase, non la password Google e non la chiave di cifratura del gioco. Le autorizzazioni dipendono da Auth e dalle regole del database, non dalla segretezza di `apiKey`. Non inserire service account, chiavi private, password o token nell'app o nella chat. Eventuali restrizioni della chiave devono consentire Firebase Authentication e Secure Token; una chiave limitata ai referrer di un sito Web puo bloccare il client Android REST.

## 4. Collegamento nell'app

1. Nella schermata iniziale inserisci **URL del database** e **Chiave API pubblica Firebase** nei due campi. Non aggiungere `.json`, `/health` o `/api` all'URL.
2. Premi Connetti. L'app effettua un accesso anonimo e controlla il percorso di verifica consentito dalle regole.
3. Crea la lobby dal telefono e copia l'invito completo accanto al codice. Chi lo riceve usa **Ho un invito → Incolla**: progetto e chiave API pubblica vengono riempiti automaticamente.
4. Ogni telefono ottiene un'identita anonima distinta. La vera chiave AES della partita e generata sul telefono organizzatore e rimane nell'invito privato; non viene caricata nel database.

I partecipanti devono usare l'app v0.8 o successiva. I collegamenti al relay Node e la configurazione PC/Render restano disponibili lasciando vuoto il campo Firebase.

## Limiti e dati

- Firebase vede indirizzi IP, identita anonime, identificatori lobby, orari, dimensioni e pacchetti cifrati. Non e una modalita senza terzi. STUN e OpenStreetMap restano quelli descritti nel README; le richieste delle tessere possono rivelare l'area visualizzata.
- Per ridurre il traffico, la partita preferisce WebRTC diretto; Firebase serve per segnalazione, disponibilita e alternativa cifrata. Le letture periodiche e l'inoltro consumano le quote del progetto.
- Le identita anonime vengono riutilizzate durante il collegamento e il relativo account viene eliminato, quando possibile, alla chiusura normale; una nuova sessione riceve una nuova identita di trasporto. Firebase limita anche il ritmo delle nuove registrazioni anonime: molti giocatori/riavvii dietro lo stesso IP possono raggiungere questi limiti.
- Le chiavi di invito e i token Auth sono mantenuti in memoria. Solo URL e chiave API pubblica vengono salvati nelle impostazioni dell'app. Perdita del processo significa nuova sessione, non ripresa automatica di una partita gia iniziata.
- La lobby viene eliminata dal database alla chiusura normale dell'organizzatore; le caselle consumate vengono ripulite. Se il processo viene arrestato o manca rete possono restare dati cifrati: **non c'e cancellazione automatica tramite una funzione cloud**. Le regole limitano i messaggi a tre ore e richiedono una presenza recente dell'organizzatore. Dalla console puoi eliminare le singole lobby terminate sotto `nogar/v1/rooms`, senza cancellare quelle in corso.
- Non viene fatturato nulla dall'app. Verifica comunque che il tuo progetto rimanga su Spark. Non abbiamo attivato o validato un progetto reale a tuo nome.

## Sviluppo e verifiche

I test Firebase usano solo il progetto fittizio `demo-nogar`, Auth Emulator e Database Emulator, con Java 21 e Node 22:

```sh
npm ci --ignore-scripts
npm run test:firebase:ci
```

Non fare `firebase deploy` senza aver selezionato consapevolmente il tuo progetto. I test non richiedono login, password Google, chiavi reali o fatturazione.

Il workflow Actions esegue le prove di lettura/scrittura delle regole e un'integrazione del client Java REST con i due emulatori. I test di connessione WebRTC e background Android su telefoni reali rimangono necessari; il comportamento di una produzione Firebase richiede anche la configurazione corretta del tuo progetto e le relative quote.

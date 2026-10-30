# Come ottenere l’URL da inserire in Nogar

L’indirizzo richiesto nella schermata iniziale è **l’URL del server condiviso**, uguale per tutti i giocatori. Si configura una volta. Dopo esserti collegato, premi **Crea lobby**: l’app genera il **codice a 6 cifre** di quella partita. Non devi creare un nuovo URL per ogni lobby.

GitHub conserva il codice e Actions compila l’APK. Per le lobby serve un servizio Node in esecuzione: GitHub Pages non lo esegue.

## Percorso semplice: Render

Il repository contiene `render.yaml`, una configurazione Blueprint pronta. Devi usare un tuo account Render e autorizzarlo a leggere il repository GitHub. Qui non è stato creato né pubblicato un server a tuo nome.

1. Apri [Render](https://render.com/) e accedi.
2. Seleziona **New → Blueprint** e collega `AtomoGentile/Escape-from-Nogare`. Se il repository è privato, concedi accesso a questo repository durante il collegamento a GitHub.
3. Seleziona il branch `main`. Render legge `render.yaml` e propone il servizio `nogar-server`.
4. Controlla il piano prima di confermare: la configurazione propone **Free**, adatto alle prove. Il servizio gratuito può andare in pausa o riavviarsi; le lobby sono in memoria e vengono perse a ogni riavvio. Per le partite continuative scegli consapevolmente un piano che resti attivo, verificandone il prezzo nel tuo account. Non aumentare il numero di istanze: le lobby di questa versione non sono condivise fra server diversi.
5. Avvia il deploy e attendi lo stato **Live**. Apri la pagina del servizio e copia il suo indirizzo HTTPS. Il nome effettivo viene assegnato da Render: un formato di esempio è `https://nogar-server-xxxx.onrender.com`, **non è un indirizzo già attivo**.
6. Per controllarlo, apri nel browser lo stesso indirizzo con `/health` alla fine. Deve rispondere con JSON contenente `"ok":true`. Su un servizio che era in pausa il primo accesso può richiedere più tempo.
7. Nell’app incolla soltanto l’indirizzo di base: **senza `/health` e senza `/api`**. Premi **Connetti**; l’app verifica che il server risponda. Tutti i partecipanti devono usare lo stesso indirizzo.
8. Crea una lobby e condividi il codice a sei cifre. Gli altri scelgono **Ho un codice**, indicano squadra e nickname ed entrano.

### Se preferisci “New → Web Service”

Collega lo stesso repository e imposta:

| Impostazione | Valore |
|---|---|
| Branch | `main` |
| Runtime | Node |
| Root Directory | `server` |
| Build Command | `node --check server.mjs` |
| Start Command | `node server.mjs` |
| Health Check Path | `/health` |
| Environment `HOST` | `0.0.0.0` |
| Environment `NODE_VERSION` | `22` |

Lascia che Render assegni `PORT`; il server la legge automaticamente. Non servono chiavi Maps o segreti per questo server. Usa il certificato HTTPS fornito dal servizio.

## Se il collegamento non riesce

- **Indirizzo non valido**: usa `https://` e copia il dominio del servizio, non il link a GitHub, alla dashboard di Render o al download dell’APK.
- **Server non raggiungibile**: verifica che sia Live e che `/health` risponda. Se il piano gratuito era in pausa, aspetta l’avvio e riprova nell’app.
- **Nessuna porta rilevata**: controlla `HOST=0.0.0.0` e il comando di avvio. Il valore predefinito locale `127.0.0.1` non espone il servizio a Render.
- **Lobby scomparsa**: un deploy, un riavvio o la pausa del servizio azzera la memoria. Create una nuova lobby quando il server è tornato attivo.

Le coordinate vengono elaborate dal server scelto dall’organizzatore. Usa il tuo servizio HTTPS e condividi indirizzo e codice solo con i partecipanti.

# Windows 11: servizio a casa senza account di hosting

Il telefono resta l'organizzatore della partita. Il PC Windows 11 ospita soltanto `nogar-relay`, che collega i partecipanti e inoltra messaggi cifrati quando il diretto non riesce.

Non devi creare account su Render o acquistare un dominio. Usiamo **sslip.io** per associare automaticamente un nome al tuo IPv4 pubblico e **Caddy** per ottenere un certificato HTTPS gratuito. Sono comunque servizi esterni DNS/certificazione: non ricevono la chiave dell'invito o le coordinate dei giocatori. STUN e tessere mappa restano quelli descritti nel README.

**Requisiti:** IPv4 WAN pubblico, porte TCP 80 e 443 raggiungibili da Internet, PC acceso e connesso per tutta la partita. Lo screenshot del router mostra un indirizzo pubblico, ma la raggiungibilita delle porte va ancora provata. Non abbiamo configurato il tuo PC o router da remoto.

## 1. Scarica codice e strumenti

Dal repository GitHub scegli **Code → Download ZIP**, poi estrai tutto in una cartella tua. Non avviare direttamente dallo ZIP.

Apri **Terminale → Windows PowerShell** e installa gli strumenti:

```powershell
winget install --id OpenJS.NodeJS.LTS --exact --source winget
winget install --id CaddyServer.Caddy --exact --source winget
```

Accetta le eventuali richieste del programma di installazione. Non serve un account di hosting. Chiudi e riapri PowerShell dopo l'installazione, poi verifica:

```powershell
node --version
caddy version
```

Serve Node 22 o successivo. Per aggiornamenti e installazione manuale usa le distribuzioni ufficiali di [Node](https://nodejs.org/) e [Caddy](https://github.com/caddyserver/caddy/releases).

## 2. Fissa l'indirizzo locale del PC e apri le porte sul router

Sul PC esegui `ipconfig` e trova l'**IPv4** della scheda Ethernet/Wi-Fi collegata al router. E' un indirizzo locale, distinto dall'IP WAN pubblico.

Nel router crea una prenotazione DHCP per il PC, cosi il suo indirizzo locale non cambia. Cerca poi **NAT / Port forwarding / Inoltro porte** e aggiungi:

| Protocollo | Porta esterna | Destinazione | Porta interna |
|---|---:|---|---:|
| TCP | 80 | IPv4 locale del PC | 80 |
| TCP | 443 | IPv4 locale del PC | 443 |

Non usare DMZ e non esporre la porta 8080. Il nome esatto dei menu dipende dal modello del router WINDTRE. Se la porta pubblica 80 o 443 e riservata all'amministrazione remota del router, verifica prima quella configurazione: non disattivare l'accesso locale al router.

## 3. Consenti Caddy nel firewall di Windows

Apri **Windows PowerShell come amministratore**, entra nella cartella estratta del repository (per esempio con `cd "C:\percorso\Escape-from-Nogare-main"`) ed esegui:

```powershell
Unblock-File .\tools\windows\*.ps1
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\tools\windows\Enable-RelayFirewall.ps1
```

Lo script aggiunge soltanto due regole in ingresso per Caddy, TCP 80 e TCP 443, sui profili privato/pubblico. Non spegne il firewall e non modifica il router. Puoi chiudere la finestra amministratore dopo questo passaggio.

## 4. Avvia il servizio

In una normale finestra PowerShell, dalla cartella del repository:

```powershell
Unblock-File .\tools\windows\*.ps1
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\tools\windows\Start-HomeRelay.ps1
```

Quando chiede **PublicIp**, inserisci l'IPv4 WAN attuale mostrato dal router. Non inserire l'IP locale del PC e non aggiungere `https://`.

Lo script:

- Controlla Node, Caddy e le porte locali, senza terminare servizi gia esistenti.
- Avvia Node soltanto su `127.0.0.1:8080`, verifica `/health` e avvia Caddy su 80/443.
- Mostra l'indirizzo HTTPS da provare e, dopo la verifica, da inserire nell'app.
- Salva la configurazione generata (con backup delle versioni precedenti) e i log in `%LOCALAPPDATA%\NogarRelay`, fuori dal repository. Il tuo indirizzo non viene scritto nei file da pubblicare su GitHub.

Il nome generato ha la forma `<IP-con-trattini>.sslip.io`. **Non esiste un indirizzo gia attivo nel progetto.** Caddy deve poter ricevere le verifiche pubbliche del certificato; il primo avvio puo richiedere tempo. DNS e autorita di certificazione possono applicare limiti: non richiedere ripetutamente certificati per configurazioni con porte chiuse.

Tieni il PC acceso, evita la sospensione durante la partita e lascia aperta la finestra. **CTRL+C** ferma i processi avviati dallo script. Chiudere forzatamente il terminale potrebbe lasciarli in esecuzione; non e un servizio Windows installato automaticamente.

## 5. Prova dall'esterno e collega l'app

Sul telefono **disattiva il Wi-Fi** e usa i dati mobili. Apri l'URL `/health` stampato dallo script.

Deve rispondere, senza avvisi del certificato, con:

```json
{"ok":true,"service":"nogar-relay","protocol":2}
```

Solo dopo questo controllo inserisci nell'app l'indirizzo di base, senza `/health`. Crea la lobby dal telefono e condividi l'invito completo. Il PC non ha bisogno di ricevere quell'invito o la sua chiave.

La prova dalla stessa rete Wi-Fi puo fallire per mancanza di NAT loopback anche se il servizio e raggiungibile da fuori: usa i dati mobili per il controllo.

## Se non funziona

- **Il controllo locale non parte:** leggi `%LOCALAPPDATA%\NogarRelay\node-error.log`; controlla che Node sia almeno 22 e che la cartella contenga `server\relay.mjs`.
- **Caddy termina o non ottiene il certificato:** leggi `caddy-error.log` nella stessa cartella. Controlla firewall, NAT, IP WAN attuale e porte occupate. Non ignorare gli errori TLS nell'app.
- **Il dominio non risolve:** sslip.io puo essere filtrato dal DNS della rete. Puoi usare un dominio tuo o una soluzione DNS differente, ma serve cambiare la configurazione; lo script non cambia i DNS del PC/router.
- **Timeout da rete mobile:** porte non inoltrate, indirizzo locale del PC cambiato, firewall oppure filtro dell'operatore. Avere un IPv4 pubblico non prova da solo l'apertura delle porte.
- **L'IP pubblico cambia:** ferma lo script e riavvialo con il nuovo indirizzo; cambieranno URL e inviti. Questo metodo non e un servizio DDNS con indirizzo fisso.

## Rimuovere le regole firewall

Dalla cartella del progetto, in PowerShell come amministratore:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\tools\windows\Disable-RelayFirewall.ps1
```

Rimuovi separatamente le due regole NAT dal router se non vuoi piu esporre il servizio. Il firewall Windows rimane attivo.

## Verifiche disponibili

Gli script vengono controllati sintatticamente e la configurazione Caddy viene verificata; l'avvio Windows, il firewall del tuo PC e la connessione WINDTRE non sono stati collaudati da questo ambiente Linux. Il controllo su rete mobile del punto 5 e necessario prima di considerare il servizio pronto.

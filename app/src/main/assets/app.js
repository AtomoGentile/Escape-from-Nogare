'use strict';
const $ = id => document.getElementById(id);
const val = id => $(id).value.trim();
let accepting = false, state = null, auth = null, fix = null;
let map = null, layer = null, own = null, accuracyRing = null, zone = null;
let busy = false, receivedAt = 0, requestAt = 0, gpsAt = 0, zoneFitted = false, selfCentered = false, activeCode = null;
let pendingAction = null, dialogFocus = null, requestedMinRadius = null;
const presets = { sprint: [10, 600, 15, .4, 25], classic: [20, 1200, 30, .4, 25], explorer: [30, 2000, 45, .5, 25] };

function show(id) {
  if (id !== 'game') setMapExpanded(false);
  ['setup', 'home', 'game'].forEach(x => $(x).classList.toggle('hidden', x !== id));
  if (id === 'game') setTimeout(() => { if (map) map.invalidateSize(); }, 100);
}
function mapExpanded() { return $('mapView').classList.contains('is-expanded'); }
function toggleMapExpanded() { setMapExpanded(!mapExpanded()); }
function setMapExpanded(expanded) {
  if (expanded === mapExpanded()) return;
  $('mapView').classList.toggle('is-expanded', expanded);
  document.body.classList.toggle('map-expanded', expanded);
  const button = $('expandMap');
  button.setAttribute('aria-expanded', String(expanded));
  button.setAttribute('aria-label', expanded ? 'Torna alla partita' : 'Apri mappa a schermo intero');
  button.querySelector('span').textContent = expanded ? 'TORNA ALLA PARTITA' : 'SCHERMO INTERO';
  button.querySelector('use').setAttribute('href', expanded ? '#i-collapse' : '#i-expand');
  button.focus({ preventScroll: true });
  // Reuse the live Leaflet instance: preserve center, zoom, GPS and ping markers.
  requestAnimationFrame(() => { if (map) map.invalidateSize({ pan: false }); });
}
function handleBack() {
  if (!$('dialog').classList.contains('hidden')) { closeDialog(); return true; }
  if (mapExpanded()) { setMapExpanded(false); return true; }
  return false;
}
function notice(message) { $('message').classList.remove('hidden'); $('message').textContent = message; }
function openSettings() {
  if (auth) return notice('Esci dalla lobby prima di cambiare server.');
  show('setup');
}
function editProfile() { $('profileEditor').classList.remove('hidden'); $('name').focus(); }
function paintProfile() {
  const hunter = val('role') === 'hunter';
  $('profileName').textContent = val('name') || 'Il tuo nickname';
  $('profileRole').textContent = hunter ? 'CACCIATORE' : 'PREDA';
  $('profileRole').classList.toggle('hunter', hunter);
  $('profileAvatar').innerHTML = '<svg class="icon" aria-hidden="true"><use href="#i-' + (hunter ? 'raven' : 'ghost') + '"/></svg>';
}
function saveProfile() {
  if (!val('name')) { notice('Scegli il tuo nickname.'); $('name').focus(); return false; }
  paintProfile(); $('profileEditor').classList.add('hidden');
  try { localStorage.setItem('nogar-profile', JSON.stringify({ name: val('name'), role: val('role') })); } catch (ignored) {}
  return true;
}
function connecting() { $('connect').disabled = true; $('connectLabel').textContent = 'VERIFICA SERVER…'; }
function connectionFailed(message) { $('connect').disabled = false; $('connectLabel').textContent = 'CONNETTI'; notice(message); }
function connected(kind) { $('serviceInfo').textContent = kind === 'pc' ? 'Server PC: il computer riceve le posizioni via HTTPS e gestisce la partita.' : 'Partita sul telefono organizzatore · messaggi cifrati.'; $('connect').disabled = false; $('connectLabel').textContent = 'CONNETTI'; show('home'); $('message').classList.add('hidden'); if(val('code').indexOf('nogar://join#')===0)mode('join'); Android.requestGps(); }
function prefillInvite(invite, relay) {
  $('server').value=relay; $('firebaseKey').value='';
  try { const url=new URL(relay); if((url.hostname.endsWith('.firebaseio.com')||url.hostname.endsWith('.firebasedatabase.app'))&&url.pathname.indexOf('/nogar-client/')===0){$('server').value=url.origin; $('firebaseKey').value=url.pathname.slice('/nogar-client/'.length);} } catch(ignored) {}
  $('code').value=invite; show('setup'); notice('Invito importato. Premi Connetti, scegli il nickname ed entra nella partita.');
}
function mode(value) {
  const create = value === 'create';
  $('createPanel').classList.toggle('hidden', !create); $('joinPanel').classList.toggle('hidden', create);
  [['tabCreate', create], ['tabJoin', !create]].forEach(pair => {
    $(pair[0]).classList.toggle('active', pair[1]); $(pair[0]).setAttribute('aria-selected', String(pair[1]));
  });
}
function preset(key, button) {
  ['minutes', 'diameter', 'ping', 'shrink', 'minRadius'].forEach((id, i) => { $(id).value = presets[key][i]; });
  document.querySelectorAll('.preset').forEach(el => {
    el.classList.toggle('selected', el === button); el.setAttribute('aria-pressed', String(el === button));
  });
  $('presetSummary').textContent = 'Ping ogni ' + presets[key][2] + ' s · ritiro del raggio ' + String(presets[key][3]).replace('.', ',') + ' m/s · raggio minimo ' + presets[key][4] + ' m';
  $('minRadius').max = Number(val('diameter')) / 2;
}
function setBusy(value) {
  busy = value;
  document.querySelectorAll('.entry').forEach(el => { el.disabled = value; });
}
function enter(action) {
  if (busy) return;
  if (!val('name')) return notice('Scegli un nickname prima di entrare.');
  if (action === 'join' && !/^nogar:\/\/join#[A-Za-z0-9_-]+$/.test(val('code'))) return notice('Incolla l’invito completo ricevuto dall’organizzatore, non il solo codice a 6 cifre.');
  const config = {};
  const limits = { minutes: [2, 120], diameter: [100, Infinity], ping: [5, 300], shrink: [0, 5], minRadius: [1, Infinity] };
  for (const key of Object.keys(limits)) {
    config[key] = Number(val(key));
    if (action === 'create' && (!val(key) || !Number.isFinite(config[key]) || config[key] < limits[key][0] || config[key] > limits[key][1])) {
      $(key).focus(); return notice('Controlla le regole: durata 2–120 min, diametro da 100 m, ping 5–300 s, ritiro 0–5 m/s, raggio minimo da 1 m.');
    }
  }
  if (action === 'create' && config.minRadius > config.diameter / 2) {
    $('minRadius').focus(); return notice('Il raggio minimo non può superare metà del diametro iniziale (' + config.diameter / 2 + ' m).');
  }
  requestedMinRadius = action === 'create' ? config.minRadius : null;
  saveProfile();
  accepting = true; state = null; auth = null;
  send({ action, name: val('name'), role: val('role'), code: val('code'), invite: val('code'), config });
}
function send(body) { setBusy(true); requestAt = performance.now(); Android.api(JSON.stringify(body)); }
function call(action, extra) {
  if (!auth || busy || (state && state.ended && action !== 'leave')) return;
  send(Object.assign({}, auth, { action }, extra || {}));
}
function networkError() {
  // Background retries belong to the native service, not a suspended JS timer.
  setBusy(false);
  notice('Connessione persa. Riprovo automaticamente durante la partita; i ping visibili possono essere obsoleti.');
  refreshClock();
}
function receive(result) {
  setBusy(false);
  if (!accepting) return;
  if (result.error) {
    if (result.errorCode === 'ROOM_GONE' || result.errorCode === 'AUTH_INVALID') trackingStopped(result.error);
    else notice(result.error);
    return;
  }
  if (result.left) return;
  if (requestedMinRadius !== null && requestedMinRadius !== 25 && result.config.minRadius === undefined) {
    accepting = false; requestedMinRadius = null;
    Android.api(JSON.stringify({ code: result.code, token: result.token, action: 'leave' }));
    notice('Aggiorna il server: questa versione non supporta ancora il raggio minimo personalizzato. La lobby non è stata mantenuta.');
    return;
  }
  requestedMinRadius = null;
  if (auth && (auth.code !== result.code || auth.token !== result.token)) return;
  if (state && result.serverTime < state.serverTime) return;
  if (activeCode !== result.code) {
    activeCode = result.code; zoneFitted = false; selfCentered = false; fix = null; gpsAt = 0;
    if (map) { layer.clearLayers(); if (own) map.removeLayer(own); if (accuracyRing) map.removeLayer(accuracyRing); own = null; accuracyRing = null; }
  }
  auth = { code: result.code, token: result.token }; state = result; receivedAt = performance.now();
  if (!result.ended && (!result.started || result.remaining > 0)) Android.startTracking(JSON.stringify(auth));
  $('message').classList.add('hidden');
  show('game'); $('lobbyCode').textContent = result.code;
  const me = result.players.find(p => p.self);
  const hunters = result.players.filter(p => p.role === 'hunter').length;
  const prey = result.players.length - hunters;
  $('count').textContent = String(result.players.length);
  $('teams').textContent = prey + ' PREDE / ' + hunters + ' CACC.';
  $('myRole').textContent = me && me.role === 'hunter' ? 'CACCIATORE' : 'PREDA';
  $('myRole').classList.toggle('hunter', !!me && me.role === 'hunter');
  $('gameRole').value = me ? me.role : 'prey';
  $('lobbyControls').classList.toggle('hidden', !!result.started || !!result.ended);
  $('start').classList.toggle('hidden', !result.host);
  const online = result.players.filter(p => p.online);
  const enough = online.some(p => p.role === 'hunter') && online.some(p => p.role === 'prey');
  $('start').disabled = !enough || !me || !me.location || me.location.accuracy > 100 || result.serverTime - me.location.at > 15000;
  $('startHint').textContent = !result.host ? 'Attendi l’avvio del creatore. Puoi ancora cambiare squadra.'
    : !enough ? 'Servono almeno una preda e un cacciatore connessi.'
    : $('start').disabled ? 'Attendi il tuo GPS preciso (±100 m o meglio) per fissare il centro.' : 'Squadre pronte. Il campo partirà dalla tua posizione GPS.';
  $('end').classList.toggle('hidden', !result.host || !result.started || !!result.ended);
  $('result').classList.toggle('hidden', !result.ended);
  if (result.ended) {
    const reasons = { timeout: 'TEMPO SCADUTO · Partita terminata.', host: 'PARTITA CHIUSA DAL CREATORE.', host_left: 'ORGANIZZATORE DISCONNESSO · Partita terminata.', team_left: 'PARTITA TERMINATA · Una squadra ha lasciato il campo.' };
    $('result').textContent = (reasons[result.ended.reason] || 'PARTITA TERMINATA.') + ' Il GPS è stato fermato.';
    fix = null; gpsAt = 0;
    if (own && map) { map.removeLayer(own); own = null; }
    if (accuracyRing && map) { map.removeLayer(accuracyRing); accuracyRing = null; }
  }
  $('rulesSummary').textContent = result.config.minutes + ' MIN / Ø ' + result.config.diameter + ' M / PING ' + result.config.ping + ' S / RITIRO ' + result.config.shrink + ' M/S / MIN ' + (result.config.minRadius === undefined ? 25 : result.config.minRadius) + ' M';
  drawMap(); renderPlayers(); refreshClock();
}
function initMap() {
  if (map || !window.L) return;
  map = L.map('map', { zoomControl: true }).setView([41.9, 12.5], 13);
  let tileErrors = 0;
  $('mapStatus').textContent = 'Caricamento mappa geografica…';
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '© OpenStreetMap contributors'
  }).on('loading', () => { tileErrors = 0; $('mapStatus').textContent = 'Caricamento mappa geografica…'; })
    .on('tileerror', () => { tileErrors++; $('mapStatus').textContent = 'Tessere non disponibili: controlla Internet prima di muoverti.'; })
    .on('load', () => { if (!tileErrors) $('mapStatus').textContent = 'Mappa online · © OpenStreetMap contributors'; }).addTo(map);
  layer = L.layerGroup().addTo(map);
}
function drawMap() {
  initMap();
  if (!map) { notice('Mappa non disponibile. Riavvia l’app e controlla Android System WebView.'); return; }
  layer.clearLayers(); zone = null;
  if (state.center) {
    zone = L.circle([state.center.lat, state.center.lng], { radius: currentRadius(), color: '#8ab900', fillColor: '#cfff42', fillOpacity: .08, weight: 3 }).addTo(layer);
    if (!zoneFitted) { fitZone(); zoneFitted = true; }
  }
  state.players.filter(p => !p.self && p.location).forEach(p => {
    const old = state.serverTime - p.location.at > state.config.ping * 2000 + 10000 || !p.online;
    const popup = document.createElement('div');
    popup.textContent = p.name + ' · ' + (p.role === 'prey' ? 'PREDA' : 'CACCIATORE') + ' · ping ' + age(p.location.at) + ' fa · ±' + Math.round(p.location.accuracy) + ' m';
    L.marker([p.location.lat, p.location.lng], { icon: L.divIcon({ className: '',
      html: '<div class="dot' + (old ? ' stale' : '') + '" style="background:' + (p.role === 'prey' ? '#b1df22' : '#ff507a') + '"></div>', iconSize: [20, 20] }) }).bindPopup(popup).addTo(layer);
  });
  paintSelf();
}
function currentElapsed() { return state ? Math.max(0, (performance.now() - receivedAt) / 1000) : 0; }
function currentRadius() { return !state ? 0 : Math.max(state.config.minRadius === undefined ? 25 : state.config.minRadius, state.radius - (state.started && !state.ended ? currentElapsed() * state.config.shrink : 0)); }
function age(timestamp) {
  const seconds = Math.max(0, Math.floor((state.serverTime - timestamp) / 1000 + currentElapsed()));
  return seconds < 60 ? seconds + ' s' : Math.floor(seconds / 60) + ' min';
}
function renderPlayers() {
  if (!state) return;
  $('players').textContent = '';
  state.players.forEach(p => {
    const row = document.createElement('div'); row.className = 'player';
    const main = document.createElement('div'); main.className = 'player-main';
    const mark = document.createElement('div'); mark.className = 'player-mark' + (p.role === 'hunter' ? ' hunter' : ''); mark.innerHTML = '<svg class="icon" aria-hidden="true"><use href="#i-' + (p.role === 'hunter' ? 'raven' : 'ghost') + '"/></svg>';
    const text = document.createElement('div'); const name = document.createElement('div'); name.className = 'player-name'; name.textContent = p.name;
    const badge = document.createElement('small'); badge.textContent = (p.self ? 'TU ' : '') + (p.host ? (state.authority === 'pc' ? 'COORD' : 'HOST') : ''); name.append(badge);
    const sub = document.createElement('div'); sub.className = 'player-sub' + (p.role === 'hunter' ? ' hunter' : ''); sub.textContent = (p.role === 'hunter' ? 'CACCIATORE' : 'PREDA') + (p.online ? '' : ' · OFFLINE');
    text.append(name, sub); main.append(mark, text);
    const position = document.createElement('div'); position.className = 'player-age';
    position.classList.toggle('old', !p.online || !!p.location && state.serverTime - p.location.at + currentElapsed() * 1000 > state.config.ping * 2000 + 10000);
    position.textContent = p.location ? age(p.location.at) + ' fa' : 'NO GPS';
    const distance = document.createElement('small');
    distance.textContent = p.location && fix && map && !p.self ? Math.round(map.distance([fix.lat, fix.lng], [p.location.lat, p.location.lng])) + ' m · in linea d’aria' : p.location ? '±' + Math.round(p.location.accuracy) + ' m' : 'In attesa di un fix';
    position.append(distance); row.append(main, position); $('players').append(row);
  });
}
function paintSelf() {
  if (!map || !fix || !state || state.ended) return;
  const point = [fix.lat, fix.lng];
  if (own) own.setLatLng(point);
  else own = L.circleMarker(point, { radius: 7, color: '#fff', fillColor: '#179bd0', fillOpacity: 1, weight: 3 }).addTo(map);
  if (accuracyRing) accuracyRing.setLatLng(point).setRadius(fix.accuracy);
  else accuracyRing = L.circle(point, { radius: fix.accuracy, color: '#1599cc', fillColor: '#49caff', fillOpacity: .08, weight: 1 }).addTo(map);
  if (!selfCentered && !state.center) { map.setView(point, 15); selfCentered = true; }
}
function onGps(lat, lng, accuracy) {
  if (!state || state.ended) return;
  fix = { lat, lng, accuracy }; gpsAt = performance.now();
  paintSelf(); refreshClock();
}
function recenter() {
  if (!fix || performance.now() - gpsAt > 15000) return notice('Attendi una posizione GPS recente prima di ricentrarti.');
  map.setView([fix.lat, fix.lng], Math.max(map.getZoom(), 16));
}
function fitZone() { if (map && zone) map.fitBounds(zone.getBounds(), { padding: [18, 18], maxZoom: 17 }); else notice('La zona viene creata quando parte la partita.'); }
function refreshClock() {
  if (!state) return;
  const elapsed = currentElapsed();
  const remaining = state.ended ? 0 : Math.max(0, state.remaining - (state.started ? elapsed : 0));
  $('phase').textContent = state.ended ? 'TERMINATA' : state.started ? 'TEMPO RESIDUO' : 'DURATA PARTITA';
  $('clock').textContent = Math.floor(remaining / 60).toString().padStart(2, '0') + ':' + Math.floor(remaining % 60).toString().padStart(2, '0');
  $('radius').textContent = Math.round(currentRadius()) + ' m';
  $('nextPing').textContent = !state.started || state.ended ? '—' : Math.max(0, Math.ceil(state.nextPingIn - elapsed)) + ' s';
  const stale = elapsed > 15;
  $('connection').classList.toggle('stale', stale);
  $('connectionText').textContent = state.ended ? 'Partita conclusa · nessuna posizione condivisa' : stale ? 'Connessione in ritardo · ping non aggiornati' : state.transport === 'pc' ? 'Server PC · posizioni via HTTPS · GPS attivo' : state.transport === 'host' ? 'Organizzatore sul telefono · messaggi cifrati' : state.transport === 'direct' ? 'Collegamento diretto cifrato · GPS attivo' : 'Inoltro cifrato · GPS in background attivo';
  const gpsFresh = fix && performance.now() - gpsAt < 15000;
  $('gps').textContent = state.ended ? 'GPS FERMO' : gpsFresh ? 'GPS ±' + Math.round(fix.accuracy) + ' M' : 'GPS IN ATTESA';
  if (own) own.setStyle({ opacity: gpsFresh ? 1 : .4, fillOpacity: gpsFresh ? 1 : .3 });
  if (zone) zone.setRadius(currentRadius());
  const outside = gpsFresh && state.center && state.started && !state.ended && map && map.distance([fix.lat, fix.lng], [state.center.lat, state.center.lng]) > currentRadius();
  $('warning').classList.toggle('hidden', !outside);
  if (outside) $('warning').textContent = 'FUORI ZONA · Il confine è virtuale. Rientra solo lungo un percorso sicuro.';
  if (busy && performance.now() - requestAt > 20000) setBusy(false);
}
function trackingStopped(message) {
  accepting = false; auth = null; state = null; fix = null; setBusy(false);
  if (map) { layer.clearLayers(); if (own) map.removeLayer(own); if (accuracyRing) map.removeLayer(accuracyRing); own = null; accuracyRing = null; }
  closeDialog(); show('home'); if (message) notice(message);
}
function trackingEnded() { refreshClock(); }
function nativeResume() { if (auth && !state.ended) trackingStopped('Il servizio GPS è stato interrotto. Entra di nuovo in una lobby.'); }
function ask(action) {
  if (!auth) return;
  pendingAction = action; dialogFocus = document.activeElement;
  $('dialogTitle').textContent = action === 'end' ? 'Chiudere la caccia?' : 'Lasciare il campo?';
  $('dialogText').textContent = action === 'end' ? 'La partita terminerà per tutti. Il GPS verrà fermato su ogni telefono connesso.' : 'La tua posizione non sarà più condivisa. Dopo l’avvio non potrai rientrare nella stessa partita.';
  $('dialog').classList.remove('hidden'); $('dialogConfirm').focus();
}
function closeDialog() { $('dialog').classList.add('hidden'); pendingAction = null; if (dialogFocus) dialogFocus.focus(); }
function confirmAction() {
  const action = pendingAction; closeDialog();
  if (action === 'end') call('end');
  if (action === 'leave') {
    Android.stopTracking(); // Native service owns the leave request and cancels its GPS first.
    trackingStopped('Condivisione interrotta. Hai lasciato la lobby.');
  }
}
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && handleBack()) { event.preventDefault(); return; }
  if (event.key === 'Tab' && mapExpanded() && $('dialog').classList.contains('hidden')) {
    const controls = Array.from($('mapView').querySelectorAll('button,a[href]')).filter(el => el.getClientRects().length);
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
  if (event.key === 'Tab' && !$('dialog').classList.contains('hidden')) {
    const buttons = $('dialog').querySelectorAll('button');
    if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[1].focus(); }
    if (!event.shiftKey && document.activeElement === buttons[1]) { event.preventDefault(); buttons[0].focus(); }
  }
});
['minutes', 'diameter', 'ping', 'shrink', 'minRadius'].forEach(id => $(id).addEventListener('input', () => {
  document.querySelectorAll('.preset').forEach(el => { el.classList.remove('selected'); el.setAttribute('aria-pressed', 'false'); });
  $('minRadius').max = Number(val('diameter')) / 2;
  $('presetSummary').textContent = 'Regole personalizzate · controlla i valori prima di avviare.';
}));
setInterval(() => { if (!document.hidden && state) { refreshClock(); renderPlayers(); } }, 1000);
$('server').value = Android.savedServer();
if(typeof Android.savedFirebaseKey==='function')$('firebaseKey').value=Android.savedFirebaseKey();

try {
  const profile = JSON.parse(localStorage.getItem('nogar-profile') || 'null');
  if (profile && typeof profile.name === 'string' && profile.name.trim() && profile.name.length <= 24 && ['prey', 'hunter'].includes(profile.role)) {
    $('name').value = profile.name; $('role').value = profile.role;
    paintProfile(); $('profileEditor').classList.add('hidden');
  }
} catch (ignored) {}
$('role').addEventListener('change', paintProfile);

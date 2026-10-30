const { test } = require('node:test');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assets = path.join(__dirname, '../app/src/main/assets');
function page(t) {
  const dom = new JSDOM(fs.readFileSync(path.join(assets, 'index.html'), 'utf8'), { runScripts: 'outside-only', url: 'https://nogar.test' });
  const w = dom.window, calls = [];
  t.after(() => w.close());
  w.Android = { savedServer: () => '', requestGps() {}, startTracking: s => calls.push(['track', JSON.parse(s)]),
    api: s => calls.push(['api', JSON.parse(s)]), stopTracking: () => calls.push(['stop']), copyCode() {} };
  const map = { setView() { return this; }, fitBounds() {}, invalidateSize() {}, removeLayer() {}, distance() { return 100; }, getZoom() { return 15; } };
  const shape = () => ({ addTo() { return this; }, on() { return this; }, clearLayers() {}, setLatLng() { return this; }, setRadius() { return this; }, setStyle() {}, bindPopup() { return this; }, getBounds() { return []; } });
  w.L = { map: () => map, tileLayer: shape, layerGroup: shape, circle: shape, circleMarker: shape, marker: shape, divIcon: x => x };
  Object.defineProperty(w.performance, 'now', { value: () => 100000 });
  new vm.Script(fs.readFileSync(path.join(assets, 'app.js'), 'utf8')).runInContext(dom.getInternalVMContext());
  const state = { code: '123456', token: 'secret', host: true, started: null, ended: null, center: null, nextPingIn: 30,
    radius: 600, remaining: 1200, serverTime: 1700000000000, config: { minutes: 20, diameter: 1200, ping: 30, shrink: .4 },
    players: [{ name: '<img src=x onerror=alert(1)>', role: 'hunter', self: true, host: true, online: true, location: { lat: 45, lng: 12, accuracy: 4, at: 1700000000000 } },
      { name: 'Ghost', role: 'prey', self: false, host: false, online: true, location: null }] };
  return { w, calls, state, el: id => w.document.getElementById(id) };
}
test('form: tab, preset, validazione e protezione dal doppio ingresso', t => {
  const { w, calls, el } = page(t);
  w.connected(); w.mode('join');
  assert.equal(el('tabJoin').getAttribute('aria-selected'), 'true');
  el('name').value = 'Ghost'; el('code').value = '12'; w.enter('join'); assert.equal(calls.length, 0);
  el('code').value = '123456'; w.enter('join'); w.enter('join');
  assert.equal(calls.filter(c => c[0] === 'api').length, 1);
  w.networkError(); assert.equal(el('joinPanel').querySelector('button').disabled, false);
  w.preset('sprint', w.document.querySelector('.preset')); assert.equal(el('minutes').value, '10');
  assert.equal(w.document.querySelector('.preset').getAttribute('aria-pressed'), 'true');
});
test('lobby: nomi come testo, squadre pronte e GPS affidato al servizio', t => {
  const { w, calls, state, el } = page(t); w.eval('accepting=true'); w.receive(state);
  assert.equal(el('players').querySelectorAll('.player').length, 2);
  assert.equal(el('players').querySelector('img'), null);
  assert.ok(el('players').textContent.includes('<img'));
  assert.equal(el('start').disabled, false);
  assert.equal(calls[0][0], 'track'); assert.equal(el('lobbyCode').textContent, '123456');
  w.onGps(45, 12, 5); assert.match(el('gps').textContent, /±5/);
});
test('timer locale, segnalazione offline, partita terminata senza riavvio GPS', t => {
  const { w, calls, state, el } = page(t); w.eval('accepting=true');
  state.started = state.serverTime; w.receive(state); w.eval('receivedAt=performance.now()-16000'); w.refreshClock();
  assert.equal(el('clock').textContent, '19:44');
  assert.ok(el('connection').classList.contains('stale'));
  const before = calls.length;
  w.receive({ ...state, serverTime: state.serverTime + 20000, ended: { reason: 'host' }, remaining: 0 });
  assert.equal(calls.length, before); assert.equal(el('gps').textContent, 'GPS FERMO');
  assert.ok(el('result').textContent.includes('CREATORE')); assert.ok(el('end').classList.contains('hidden'));
});
test('uscita confermata: ferma il servizio e ignora risposte tardive', t => {
  const { w, calls, state, el } = page(t); w.eval('accepting=true'); w.receive(state);
  w.ask('leave'); assert.equal(el('dialog').classList.contains('hidden'), false);
  w.confirmAction(); assert.ok(calls.some(c => c[0] === 'stop'));
  w.receive(state); assert.equal(el('game').classList.contains('hidden'), true);
});

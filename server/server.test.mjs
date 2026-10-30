import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './server.mjs';
const proof = 'a'.repeat(64);
const config = { minutes: 20, diameter: 1200, ping: 30, shrink: .4 };
async function fixture(t, overrides = {}) {
  let now = 1700000000000;
  const server = createServer({ clock: () => now });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const url = `http://127.0.0.1:${server.address().port}/api`;
  async function api(body) {
    const r = await fetch(url, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(Array.isArray(body)?body:{ ...((body.action==='create'||body.action==='join')?{joinProof:proof}:{}), ...body }) });
    return { status: r.status, ...await r.json() };
  }
  const auth = r => ({ code: r.code, token: r.token });
  const host = auth(await api({ action: 'create', name: 'Host', role: 'hunter', config: { ...config, ...overrides } }));
  const prey = auth(await api({ action: 'join', code: host.code, name: 'Ghost', role: 'prey' }));
  const gps = (who = host, extra = {}) => api({ ...who, action: 'location', location: { lat: 45, lng: 12, accuracy: 4, ...extra } });
  return { api, host, prey, gps, advance: ms => { now += ms; } };
}
test('ping condivisi: prima scadenza, throttling e riservatezza dei token', async t => {
  const f = await fixture(t);
  assert.equal((await f.api({ ...f.prey, action: 'start' })).status, 403);
  assert.equal((await f.api({ ...f.host, action: 'start' })).status, 400);
  await f.gps(); await f.gps(f.prey);
  const start = await f.api({ ...f.host, action: 'start' });
  assert.equal(start.config.minRadius, 25); assert.equal(start.center.lat, 45); assert.equal(start.nextPingIn, 30);
  let r = await f.gps(f.prey, { lat: 45.1, extra: 'must not leak' });
  assert.equal(r.players.find(p => p.self).location.lat, 45);
  f.advance(30000); r = await f.gps(f.prey, { lat: 45.1, extra: 'must not leak' });
  assert.equal(r.players.find(p => p.self).location.lat, 45.1);
  assert.equal(r.players.find(p => p.self).location.extra, undefined);
  assert.ok(r.players.every(p => !('id' in p) && !('token' in p)));
  assert.equal((await f.api({ action: 'join', code: f.host.code, name: 'Late', role: 'prey' })).status, 409);
  assert.equal((await f.api({ ...f.host, token: 'bad', action: 'poll' })).status, 401);
});
test('cambio squadra prepartita, divieto dopo avvio, chiusura host e cancellazione coordinate', async t => {
  const f = await fixture(t);
  let r = await f.api({ ...f.prey, action: 'role', role: 'hunter' });
  assert.equal(r.players.find(p => p.self).role, 'hunter');
  await f.api({ ...f.prey, action: 'role', role: 'prey' });
  await f.gps(); await f.gps(f.prey); await f.api({ ...f.host, action: 'start' });
  assert.equal((await f.api({ ...f.prey, action: 'role', role: 'hunter' })).status, 409);
  assert.equal((await f.api({ ...f.prey, action: 'end' })).status, 403);
  r = await f.api({ ...f.host, action: 'end' });
  assert.equal(r.ended.reason, 'host'); assert.equal(r.remaining, 0);
  assert.ok(r.players.every(p => p.location === null));
  r = await f.gps(); assert.ok(r.players.every(p => p.location === null));
});
test('tempo server: ritiro e arresto esatto alla scadenza anche con un poll tardivo', async t => {
  const f = await fixture(t); await f.gps(); await f.api({ ...f.host, action: 'start' });
  f.advance(100000); let r = await f.api({ ...f.host, action: 'poll' });
  assert.equal(r.radius, 560); assert.equal(r.remaining, 1100);
  f.advance(1400000); r = await f.gps();
  assert.equal(r.ended.reason, 'timeout'); assert.equal(r.remaining, 0); assert.equal(r.radius, 120);
  assert.ok(r.players.every(p => !p.location));
});
test('nickname, JSON, configurazione, GPS e codici invalidi sono respinti', async t => {
  const f = await fixture(t);
  assert.equal((await f.api('oops')).status, 400);
  assert.equal((await f.api([])).status, 400);
  assert.equal((await f.api({ action: 'create', name: 'A', role: 'prey', config: { ...config, shrink: -1 } })).status, 400);
  assert.equal((await f.api({ action: 'join', code: f.host.code, name: 'ghost', role: 'prey' })).status, 409);
  assert.equal((await f.api({ action: 'join', code: 'x', name: 'A', role: 'prey' })).status, 400);
  assert.equal((await f.gps(f.host, { lat: 91 })).status, 400);
  assert.equal((await f.gps(f.host, { accuracy: -1 })).status, 400);
});
test('presenza online, trasferimento host e fine partita quando una squadra esce', async t => {
  const f = await fixture(t); await f.gps(); f.advance(31000); await f.gps();
  assert.equal((await f.api({ ...f.host, action: 'start' })).status, 400);
  await f.api({ ...f.prey, action: 'poll' }); await f.api({ ...f.host, action: 'start' });
  await f.api({ ...f.host, action: 'leave' });
  const r = await f.api({ ...f.prey, action: 'poll' });
  assert.equal(r.host, true); assert.equal(r.ended.reason, 'team_left');
  await f.api({ ...f.prey, action: 'leave' });
  assert.equal((await f.api({ ...f.prey, action: 'poll' })).errorCode, 'ROOM_GONE');
});
test('lobby inattive scadono e i limiti richieste sono per giocatore', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 120; i++) assert.equal((await f.api({ ...f.host, action: 'poll' })).status, 200);
  assert.equal((await f.api({ ...f.host, action: 'poll' })).status, 429);
  assert.equal((await f.api({ ...f.prey, action: 'poll' })).status, 200);
  f.advance(6 * 3600000 + 1);
  assert.equal((await f.api({ ...f.prey, action: 'poll' })).errorCode, 'ROOM_GONE');
});
test('health check pubblico identifica il server senza esporre lobby o coordinate', async t => {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const response = await fetch(`http://127.0.0.1:${server.address().port}/health`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), { ok: true, service: 'nogar-pc', protocol: 3 });
});

test('il raggio minimo scelto viene condiviso e ferma il ritiro prima della fine', async t => {
  const f = await fixture(t, { minRadius: 200 });
  await f.gps(); await f.api({ ...f.host, action: 'start' });
  f.advance(1100000);
  const result = await f.api({ ...f.prey, action: 'poll' });
  assert.equal(result.config.minRadius, 200);
  assert.equal(result.radius, 200);
  assert.equal(result.remaining, 100);
  assert.equal(result.ended, null);
});
test('limiti del raggio minimo: positivo e non oltre metà diametro', async t => {
  const f = await fixture(t);
  for (const minRadius of [0, -1, 601, null, '25']) {
    const result = await f.api({ action: 'create', name: 'Invalid', role: 'hunter', config: { ...config, minRadius } });
    assert.equal(result.status, 400, 'must reject ' + minRadius);
  }
  for (const minRadius of [1, 600]) {
    const result = await f.api({ action: 'create', name: 'Valid', role: 'hunter', config: { ...config, minRadius } });
    assert.equal(result.status, 200);
    assert.equal(result.config.minRadius, minRadius);
  }
});

test('invito privato obbligatorio e autorita PC indipendente dal creatore', async t => {
  const f = await fixture(t);
  for (const joinProof of [null, '', 'b'.repeat(64)]) {
    assert.equal((await f.api({action:'join',code:f.host.code,name:'Intruso',role:'hunter',joinProof})).errorCode,'INVITE_INVALID');
  }
  const extra = await f.api({action:'join',code:f.host.code,name:'Secondo',role:'hunter'});
  assert.equal(extra.joinProof, undefined);
  await f.gps(); await f.api({...f.host,action:'start'});
  await f.api({...f.host,action:'leave'});
  const result=await f.api({...f.prey,action:'poll'});
  assert.equal(result.ended,null); assert.equal(result.host,true); assert.equal(result.authority,'pc');
  assert.equal(result.players.length,2);
});

test('diametro oltre 10 km senza tetto fisso e minimo proporzionale',async t=>{
  const f=await fixture(t,{diameter:50000,minRadius:12000});
  const result=await f.api({...f.host,action:'poll'});
  assert.equal(result.config.diameter,50000);assert.equal(result.radius,25000);
  assert.equal(result.config.minRadius,12000);
  assert.equal((await f.api({action:'create',name:'Invalid',role:'hunter',config:{...config,diameter:50000,minRadius:25001}})).status,400);
});

import http from 'node:http';
import { randomBytes, randomInt } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const presets = {
  sprint: { label: 'SPRINT · 10 min', minutes: 10, diameter: 600, ping: 15, shrink: 0.4, minRadius: 25 },
  classic: { label: 'CLASSIC · 20 min', minutes: 20, diameter: 1200, ping: 30, shrink: 0.4, minRadius: 25 },
  explorer: { label: 'EXPLORER · 30 min', minutes: 30, diameter: 2000, ping: 45, shrink: 0.5, minRadius: 25 }
};
const secret = () => randomBytes(24).toString('hex');
const numeric = (v, min, max) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
const fail = (status, message, errorCode = 'INVALID_REQUEST') => {
  throw Object.assign(new Error(message), { status, errorCode });
};

export function createServer({ clock = Date.now } = {}) {
  const rooms = new Map(), rates = new Map();
  function cleanup() {
    const now = clock();
    for (const [key, room] of rooms) if (now - room.updated > 6 * 3600000) rooms.delete(key);
    for (const [key, rate] of rates) if (now - rate.at > 60000) rates.delete(key);
  }
  function limit(key, max) {
    const now = clock();
    let rate = rates.get(key);
    if (!rate || now - rate.at > 60000) rate = { at: now, n: 0 };
    rates.set(key, rate);
    if (++rate.n > max) fail(429, 'Troppi tentativi. Riprova tra un minuto.', 'RATE_LIMIT');
  }
  function end(room, reason) {
    if (room.ended) return;
    room.ended = { at: clock(), reason };
    // No location history or retained coordinates once the game is over.
    for (const p of room.players) p.location = null;
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      cleanup();
      if (req.method === 'GET' && req.url === '/health') { res.end('{"ok":true,"service":"nogar","protocol":1}'); return; }
      if (req.method !== 'POST' || req.url !== '/api') fail(404, 'Endpoint inesistente');
      // Coarse abuse limit, not the per-player budget. Reverse proxies may share one IP.
      limit('ip:' + req.socket.remoteAddress, 10000);
      let raw = '';
      for await (const part of req) {
        raw += part;
        if (Buffer.byteLength(raw) > 8192) fail(413, 'Richiesta troppo grande');
      }
      let b;
      try { b = JSON.parse(raw); } catch { fail(400, 'JSON non valido'); }
      if (!b || typeof b !== 'object' || Array.isArray(b)) fail(400, 'Richiesta non valida');
      const now = clock();
      let room, player;
      if (b.action === 'create' || b.action === 'join') {
        limit('entry:' + req.socket.remoteAddress, 60);
        if (typeof b.name !== 'string' || !b.name.trim() || b.name.trim().length > 24)
          fail(400, 'Nome richiesto (massimo 24 caratteri)');
        if (!['hunter', 'prey'].includes(b.role)) fail(400, 'Squadra non valida');
        if (b.action === 'create') {
          const c = b.config;
          if (!c || !numeric(c.minutes, 2, 120) || !numeric(c.diameter, 100, 10000)
              || !numeric(c.ping, 5, 300) || !numeric(c.shrink, 0, 5)) fail(400, 'Impostazioni non valide');
          const minRadius = c.minRadius === undefined ? 25 : c.minRadius;
          if (!numeric(minRadius, 1, c.diameter / 2)) fail(400, 'Il raggio minimo deve essere tra 1 m e metà del diametro iniziale');
          if (rooms.size >= 1000) fail(503, 'Server pieno');
          let code;
          do { code = String(randomInt(100000, 1000000)); } while (rooms.has(code));
          room = { code, config: { minutes: c.minutes, diameter: c.diameter, ping: c.ping, shrink: c.shrink, minRadius },
            players: [], host: null, started: null, ended: null, center: null, updated: now };
          rooms.set(code, room);
        } else {
          if (typeof b.code !== 'string' || !/^\d{6}$/.test(b.code)) fail(400, 'Il codice deve avere 6 cifre');
          room = rooms.get(b.code);
          if (!room) fail(404, 'Lobby non trovata', 'ROOM_GONE');
          if (room.started || room.ended) fail(409, 'Partita già iniziata o terminata');
        }
        if (room.players.length >= 50) fail(409, 'Lobby piena (50 giocatori)');
        if (room.players.some(p => p.name.toLocaleLowerCase('it') === b.name.trim().toLocaleLowerCase('it')))
          fail(409, 'Nickname già usato in questa lobby');
        player = { id: secret(), name: b.name.trim(), role: b.role, location: null, lastPing: null, seen: now };
        room.players.push(player);
        if (!room.host) room.host = player.id;
      } else {
        room = rooms.get(String(b.code));
        if (!room) fail(404, 'Lobby scaduta o inesistente', 'ROOM_GONE');
        player = room.players.find(p => p.id === b.token);
        if (!player) fail(401, 'Accesso non valido', 'AUTH_INVALID');
        limit('player:' + player.id, 120);
        player.seen = now;
        if (room.started && now - room.started >= room.config.minutes * 60000) end(room, 'timeout');
        if (b.action === 'location') {
          const l = b.location;
          if (!l || !numeric(l.lat, -90, 90) || !numeric(l.lng, -180, 180)
              || !numeric(l.accuracy, 0, 1000)) fail(400, 'Posizione non valida');
          if (!room.ended && (!room.started || player.lastPing === null || now - player.lastPing >= room.config.ping * 1000)) {
            player.location = { lat: l.lat, lng: l.lng, accuracy: l.accuracy, at: now };
            player.lastPing = now;
          }
        } else if (b.action === 'role') {
          if (room.started || room.ended) fail(409, 'Le squadre sono bloccate dopo l’avvio');
          if (!['hunter', 'prey'].includes(b.role)) fail(400, 'Squadra non valida');
          player.role = b.role;
        } else if (b.action === 'start') {
          if (player.id !== room.host) fail(403, 'Solo il creatore può avviare');
          if (room.started || room.ended) fail(409, 'Partita già iniziata o terminata');
          const online = room.players.filter(p => now - p.seen < 30000);
          if (!online.some(p => p.role === 'hunter') || !online.some(p => p.role === 'prey'))
            fail(400, 'Servono almeno un cacciatore e una preda connessi');
          if (!player.location || now - player.location.at > 15000 || player.location.accuracy > 100)
            fail(400, 'Attendi una posizione GPS recente e precisa (entro 100 m)');
          room.center = { lat: player.location.lat, lng: player.location.lng };
          room.started = now;
          // One common ping cadence from the start, avoiding exposure from lobby movement.
          for (const p of room.players) p.lastPing = now;
        } else if (b.action === 'end') {
          if (player.id !== room.host) fail(403, 'Solo il creatore può terminare la partita');
          if (!room.started) fail(409, 'Partita non ancora iniziata');
          end(room, 'host');
        } else if (b.action === 'leave') {
          room.players = room.players.filter(p => p !== player);
          if (room.host === player.id) room.host = room.players[0]?.id;
          if (!room.players.length) rooms.delete(room.code);
          else if (room.started && (!room.players.some(p => p.role === 'hunter') || !room.players.some(p => p.role === 'prey')))
            end(room, 'team_left');
          res.end('{"left":true}'); return;
        } else if (b.action !== 'poll') fail(400, 'Azione non valida');
      }
      room.updated = now;
      const elapsed = room.started ? Math.max(0, ((room.ended?.at || now) - room.started) / 1000) : 0;
      const nextPingIn = room.started && !room.ended
        ? Math.max(0, room.config.ping - (now - (player.lastPing ?? room.started)) / 1000) : 0;
      res.end(JSON.stringify({ code: room.code, token: player.id, host: room.host === player.id,
        config: room.config, started: room.started, ended: room.ended, center: room.center, serverTime: now,
        nextPingIn, remaining: room.ended ? 0 : Math.max(0, room.config.minutes * 60 - elapsed),
        radius: Math.max(room.config.minRadius, room.config.diameter / 2 - elapsed * room.config.shrink),
        players: room.players.map(p => ({ name: p.name, role: p.role, self: p === player,
          host: p.id === room.host, online: now - p.seen < 30000, location: p.location })) }));
    } catch (e) {
      res.statusCode = e.status || 500;
      res.end(JSON.stringify({ error: e.status ? e.message : 'Errore interno', errorCode: e.errorCode || 'INTERNAL' }));
    }
  });
  server.requestTimeout = 15000;
  const timer = setInterval(cleanup, 60000);
  timer.unref();
  server.on('close', () => clearInterval(timer));
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  createServer().listen(Number(process.env.PORT || 8080), process.env.HOST || '127.0.0.1', () => console.log('Nogar server listening'));

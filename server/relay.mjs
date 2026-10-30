// Opaque mailbox only: never receives the invite encryption key or game state.
import http from 'node:http';
import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
const random = () => randomBytes(24).toString('base64url');
const equal = (a,b) => typeof a==='string' && typeof b==='string' && Buffer.byteLength(a)===Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a),Buffer.from(b));
export function createRelay({ clock=Date.now }={}) {
 const rooms=new Map(), rates=new Map(); let queuedBytes=0;
 const fail=(status,error,errorCode='RELAY_ERROR')=>{throw Object.assign(new Error(error),{status,errorCode})};
 const drop=(room)=>{for(const p of room.peers.values())queuedBytes-=p.bytes;rooms.delete(room.code)};
 function cleanup(){const now=clock();for(const room of rooms.values())if(now-room.hostSeen>120000||now-room.created>3*3600000)drop(room);for(const [k,v] of rates)if(now-v.at>60000)rates.delete(k)}
 function queue(room,to,event){const p=room.peers.get(to);if(!p)return;const size=Buffer.byteLength(JSON.stringify(event));if(size>100000)fail(413,'Messaggio troppo grande');while(p.events.length&&(p.bytes+size>256000||p.events.length>=128)){const old=p.events.shift();p.bytes-=old.bytes;queuedBytes-=old.bytes}if(queuedBytes+size>32*1024*1024)fail(503,'Relay occupato');p.events.push({...event,cursor:++p.cursor,bytes:size});p.bytes+=size;queuedBytes+=size}
 const server=http.createServer(async(req,res)=>{
  res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');
  try{
   cleanup();
   if(req.method==='GET'&&req.url==='/health'){res.end('{"ok":true,"service":"nogar-relay","protocol":2}');return}
   if(req.method!=='POST'||req.url!=='/api')fail(404,'Endpoint inesistente');
   const ip=req.socket.remoteAddress,now=clock();let rate=rates.get(ip)||{at:now,n:0};if(++rate.n>12000)fail(429,'Troppi tentativi');rates.set(ip,rate);
   let raw='';for await(const chunk of req){raw+=chunk;if(Buffer.byteLength(raw)>1048576)fail(413,'Richiesta troppo grande')}
   let b;try{b=JSON.parse(raw)}catch{fail(400,'JSON non valido')}
   if(!b||Array.isArray(b)||typeof b!=='object')fail(400,'Richiesta non valida');
   if(b.action==='create'){
    if(typeof b.admission!=='string'||! /^[a-f0-9]{64}$/.test(b.admission))fail(400,'Invito non valido');
    if(rooms.size>=500)fail(503,'Relay pieno');
    let code;do{code=String(randomInt(100000,1000000))}while(rooms.has(code));
    const token=random(),host={token,events:[],bytes:0,cursor:0};
    rooms.set(code,{code,admission:b.admission,created:now,hostSeen:now,peers:new Map([['host',host]])});
    res.end(JSON.stringify({code,peer:'host',token}));return;
   }
   const room=rooms.get(b.code);if(!room)fail(404,'Organizzatore non raggiungibile o partita scaduta','ROOM_GONE');
   if(b.action==='join'){
    if(!equal(b.admission,room.admission))fail(403,'Invito non valido','INVITE_INVALID');
    if(room.peers.size>=50)fail(409,'Lobby piena');
    const peer=random(),token=random();room.peers.set(peer,{token,events:[],bytes:0,cursor:0});queue(room,'host',{kind:'peer',from:peer});
    res.end(JSON.stringify({code:room.code,peer,token}));return;
   }
   const peer=room.peers.get(b.peer);if(!peer||!equal(b.token,peer.token))fail(401,'Accesso non valido','AUTH_INVALID');
   if(b.peer==='host')room.hostSeen=now;
   if(b.action==='leave'){
    if(b.peer==='host')drop(room);
    else{queuedBytes-=peer.bytes;room.peers.delete(b.peer);queue(room,'host',{kind:'left',from:b.peer})}
    res.end('{"left":true}');return;
   }
   if(b.action!=='exchange')fail(400,'Azione non valida');
   if(!Number.isSafeInteger(b.cursor)||b.cursor<0||b.cursor>peer.cursor)fail(400,'Cursore non valido');
   if(!Array.isArray(b.packets)||b.packets.length>50)fail(400,'Pacchetti non validi');
   // Validate the full batch before forwarding anything. Sender identity is server-derived.
   for(const p of b.packets){if(!p||typeof p.to!=='string'||p.to===b.peer||(b.peer!=='host'&&p.to!=='host')||typeof p.packet!=='string'||p.packet.length>90000||! /^[A-Za-z0-9_-]+$/.test(p.packet))fail(400,'Destinazione o pacchetto non valido')}
   while(peer.events.length&&peer.events[0].cursor<=b.cursor){const old=peer.events.shift();peer.bytes-=old.bytes;queuedBytes-=old.bytes}
   for(const p of b.packets)queue(room,p.to,{kind:'message',from:b.peer,packet:p.packet});
   res.end(JSON.stringify({events:peer.events.map(({bytes,...event})=>event),cursor:peer.cursor}));
  }catch(e){res.statusCode=e.status||500;res.end(JSON.stringify({error:e.status?e.message:'Errore interno',errorCode:e.errorCode||'INTERNAL'}))}
 });
 server.requestTimeout=15000;const timer=setInterval(cleanup,30000);timer.unref();server.on('close',()=>clearInterval(timer));return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)createRelay().listen(Number(process.env.PORT||8080),process.env.HOST||'127.0.0.1',()=>console.log('Nogar encrypted relay listening'));

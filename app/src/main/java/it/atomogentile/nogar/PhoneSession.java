package it.atomogentile.nogar;

import android.content.Context;
import android.os.SystemClock;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import org.webrtc.*;

/** Star topology: authoritative phone + authenticated encrypted peer messages. */
final class PhoneSession implements AutoCloseable {
    private final String endpoint,code,peer,token,invitation;
    private final PeerCrypto crypto;
    private final PhoneGame game;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService rtc=Executors.newSingleThreadExecutor();
    private final ConcurrentLinkedQueue<JSONObject> outbox=new ConcurrentLinkedQueue<>();
    private final Map<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final Map<String,PeerConnection> connections=new ConcurrentHashMap<>();
    private final Map<String,DataChannel> channels=new ConcurrentHashMap<>();
    private final Map<String,List<IceCandidate>> candidates=new HashMap<>();
    private final Set<String> remoteReady=new HashSet<>();
    private final Map<String,AtomicLong> counters=new ConcurrentHashMap<>();
    private final Map<String,PeerCrypto.ReplayWindow> replay=new HashMap<>();
    private final LinkedHashMap<String,String> replies=new LinkedHashMap<String,String>() {
        protected boolean removeEldestEntry(Map.Entry<String,String> e){return size()>1024;}
    };
    private final PeerConnectionFactory factory;
    private volatile boolean closed, joined;
    private volatile String fatal;
    private long cursor;
    private static boolean initialized;

    static PhoneSession open(Context context,String endpoint,JSONObject creation) throws Exception {
        final byte[] key;final JSONObject bootstrap;
        if(creation.getString("action").equals("create")) {
            key=PeerCrypto.newKey();bootstrap=ApiClient.post(endpoint,new JSONObject().put("action","create").put("admission",PeerCrypto.admission(key)));
        } else {
            PeerCrypto.Invitation invite=new PeerCrypto.Invitation(creation.getString("invite"));
            if(!invite.relay.equals(endpoint))throw new IllegalArgumentException("L’invito usa un altro servizio: collega prima il suo indirizzo HTTPS");
            key=invite.key;bootstrap=ApiClient.post(endpoint,new JSONObject().put("action","join").put("code",invite.code).put("admission",PeerCrypto.admission(key)));
        }
        if(bootstrap.has("error"))throw new IllegalArgumentException(bootstrap.getString("error"));
        try{return new PhoneSession(context,endpoint,key,bootstrap,creation);}catch(Exception e){
            try{ApiClient.post(endpoint,new JSONObject().put("action","leave").put("code",bootstrap.getString("code")).put("peer",bootstrap.getString("peer")).put("token",bootstrap.getString("token")));}catch(Exception ignored){}throw e;
        }
    }
    private PhoneSession(Context context,String endpoint,byte[] key,JSONObject bootstrap,JSONObject creation) throws Exception {
        this.endpoint=endpoint;code=bootstrap.getString("code");peer=bootstrap.getString("peer");token=bootstrap.getString("token");
        crypto=new PeerCrypto(key,code);invitation=PeerCrypto.invite(endpoint,code,key);
        long epoch=System.currentTimeMillis(),monotonic=SystemClock.elapsedRealtime();
        game=peer.equals("host")?new PhoneGame(code,creation,()->epoch+SystemClock.elapsedRealtime()-monotonic):null; joined=game!=null;
        synchronized(PhoneSession.class){if(!initialized){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.getApplicationContext()).createInitializationOptions());initialized=true;}}
        factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
        io.scheduleWithFixedDelay(this::exchange,0,1000,TimeUnit.MILLISECONDS);
    }
    String invitation(){return invitation;}
    String code(){return code;}
    JSONObject request(JSONObject body) throws Exception {
        if(closed)throw new IllegalStateException("Sessione chiusa");
        if(fatal!=null)return new JSONObject().put("error",fatal).put("errorCode","ROOM_GONE");
        JSONObject result;
        if(game!=null)result=game.handle("host",body);
        else {
            String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> future=new CompletableFuture<>();pending.put(id,future);
            JSONObject message=new JSONObject().put("kind","request").put("id",id).put("body",body);
            try{
                send("host",message,false);
                try{result=future.get(8,TimeUnit.SECONDS);}catch(TimeoutException timeout){send("host",message,true);result=future.get(10,TimeUnit.SECONDS);}
            }finally{pending.remove(id);}
        }
        if(!result.has("error")&&!result.optBoolean("left")){
            joined=true;
            // Invitation stays only on participating phones; never part of relay metadata.
            result.put("invitation",invitation).put("transport",game!=null?"host":result.optString("transport","relay"));
        }
        return result;
    }
    private boolean direct(String to){DataChannel c=channels.get(to);return c!=null&&c.state()==DataChannel.State.OPEN;}
    private void send(String to,JSONObject message,boolean forceRelay) throws Exception {
        if(closed)return;
        JSONObject payload=new JSONObject(message.toString()).put("seq",counters.computeIfAbsent(to,k->new AtomicLong()).incrementAndGet());
        String packet=crypto.seal(peer,to,payload);
        DataChannel channel=channels.get(to);
        if(!forceRelay&&channel!=null&&channel.state()==DataChannel.State.OPEN&&channel.bufferedAmount()<262144
                &&channel.send(new DataChannel.Buffer(ByteBuffer.wrap(packet.getBytes(StandardCharsets.US_ASCII)),false)))return;
        if(outbox.size()>=256)throw new IllegalStateException("Connessione in ritardo");
        outbox.add(new JSONObject().put("to",to).put("packet",packet));
    }
    private void exchange(){
        if(closed)return;List<JSONObject> batch=new ArrayList<>();
        try {
            JSONArray packets=new JSONArray();for(int i=0;i<50;i++){JSONObject p=outbox.poll();if(p==null)break;batch.add(p);packets.put(p);}
            JSONObject result=ApiClient.post(endpoint,new JSONObject().put("action","exchange").put("code",code).put("peer",peer).put("token",token).put("cursor",cursor).put("packets",packets));
            if(closed)return;
            if(result.has("error")){
                String err=result.optString("errorCode");if(err.equals("ROOM_GONE")||err.equals("AUTH_INVALID")){fatal=result.getString("error");for(CompletableFuture<JSONObject> f:pending.values())f.completeExceptionally(new IllegalStateException(fatal));}
                else for(JSONObject p:batch)outbox.add(p);return;
            }
            JSONArray events=result.getJSONArray("events");
            for(int i=0;i<events.length();i++){
                JSONObject event=events.getJSONObject(i);String from=event.getString("from"),kind=event.getString("kind");
                if(kind.equals("peer")&&game!=null)dispatch(()->offer(from));
                else if(kind.equals("left")&&game!=null)dispatch(()->{try{game.handle(from,new JSONObject().put("action","leave"));}catch(Exception ignored){}disposePeer(from);});
                else if(kind.equals("message")){String packet=event.getString("packet");dispatch(()->receive(from,packet,false));}
            }
            cursor=result.getLong("cursor");
        }catch(Exception ignored){if(!closed)for(JSONObject p:batch)if(outbox.size()<256)outbox.add(p);}
    }
    private void receive(String from,String packet,boolean viaDirect){
        if(closed||(!peer.equals("host")&&!from.equals("host")))return;
        try {
            JSONObject message=crypto.open(from,peer,packet);
            if(!replay.computeIfAbsent(from,k->new PeerCrypto.ReplayWindow()).accept(message.getLong("seq")))return;
            String kind=message.getString("kind");
            if(kind.equals("signal")){signal(from,message);return;}
            if(kind.equals("response")&&game==null){CompletableFuture<JSONObject> f=pending.get(message.getString("id"));if(f!=null)f.complete(message.getJSONObject("body").put("transport",viaDirect?"direct":"relay"));return;}
            if(kind.equals("request")&&game!=null){
                String id=message.getString("id");if(id.length()>64)return;String cacheKey=from+":"+id;
                String result=replies.get(cacheKey);
                if(result==null){result=game.handle(from,message.getJSONObject("body")).toString();replies.put(cacheKey,result);}
                send(from,new JSONObject().put("kind","response").put("id",id).put("body",new JSONObject(result)),!viaDirect);
            }
        }catch(Exception ignored){/* Authentication failure is not game input. */}
    }
    private PeerConnection connection(String to){
        PeerConnection existing=connections.get(to);if(existing!=null)return existing;
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection pc=factory.createPeerConnection(config,new PeerConnection.Observer(){
            public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
            public void onIceConnectionReceivingChange(boolean b){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            public void onIceCandidate(IceCandidate c){dispatch(()->{try{send(to,new JSONObject().put("kind","signal").put("type","ice").put("mid",c.sdpMid).put("line",c.sdpMLineIndex).put("candidate",c.sdp),true);}catch(Exception ignored){}});}
            public void onIceCandidatesRemoved(IceCandidate[] c){}
            public void onAddStream(MediaStream s){}
            public void onRemoveStream(MediaStream s){}
            public void onDataChannel(DataChannel channel){dispatch(()->attach(to,channel));}
            public void onRenegotiationNeeded(){}
        });
        if(pc!=null)connections.put(to,pc);return pc;
    }
    private void attach(String to,DataChannel channel){
        if(closed){channel.close();channel.dispose();return;}
        DataChannel old=channels.put(to,channel);if(old!=null&&old!=channel){old.unregisterObserver();old.close();old.dispose();}
        channel.registerObserver(new DataChannel.Observer(){
            public void onBufferedAmountChange(long previous){}
            public void onStateChange(){}
            public void onMessage(DataChannel.Buffer buffer){if(buffer.data.remaining()>90000)return;byte[] bytes=new byte[buffer.data.remaining()];buffer.data.get(bytes);String packet=new String(bytes,StandardCharsets.US_ASCII);dispatch(()->receive(to,packet,true));}
        });
    }
    private static class Sdp implements SdpObserver {
        public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String e){}public void onSetFailure(String e){}
    }
    private void offer(String to){
        if(closed||connections.containsKey(to))return;PeerConnection pc=connection(to);if(pc==null)return;
        attach(to,pc.createDataChannel("nogar",new DataChannel.Init()));
        pc.createOffer(new Sdp(){public void onCreateSuccess(SessionDescription s){dispatch(()->local(to,pc,s));}},new MediaConstraints());
    }
    private void local(String to,PeerConnection pc,SessionDescription description){
        if(closed)return;
        pc.setLocalDescription(new Sdp(){public void onSetSuccess(){try{send(to,new JSONObject().put("kind","signal").put("type",description.type.canonicalForm()).put("sdp",description.description),true);}catch(Exception ignored){}}},description);
    }
    private void signal(String from,JSONObject message) throws Exception {
        String type=message.getString("type");PeerConnection pc=connection(from);if(pc==null)return;
        if(type.equals("ice")){
            IceCandidate candidate=new IceCandidate(message.optString("mid"),message.getInt("line"),message.getString("candidate"));
            if(remoteReady.contains(from))pc.addIceCandidate(candidate);else {List<IceCandidate> list=candidates.computeIfAbsent(from,k->new ArrayList<>());if(list.size()<256)list.add(candidate);}return;
        }
        if(!(type.equals("offer")&&game==null||type.equals("answer")&&game!=null))return;
        pc.setRemoteDescription(new Sdp(){public void onSetSuccess(){dispatch(()->{
            remoteReady.add(from);List<IceCandidate> list=candidates.remove(from);if(list!=null)for(IceCandidate c:list)pc.addIceCandidate(c);
            if(type.equals("offer"))pc.createAnswer(new Sdp(){public void onCreateSuccess(SessionDescription s){dispatch(()->local(from,pc,s));}},new MediaConstraints());
        });}},new SessionDescription(SessionDescription.Type.fromCanonicalForm(type),message.getString("sdp")));
    }
    private void disposePeer(String id){DataChannel channel=channels.remove(id);if(channel!=null){channel.unregisterObserver();channel.close();channel.dispose();}PeerConnection pc=connections.remove(id);if(pc!=null){pc.close();pc.dispose();}candidates.remove(id);remoteReady.remove(id);}
    private void dispatch(Runnable task){try{rtc.execute(task);}catch(RejectedExecutionException ignored){}}
    @Override public void close(){finish();}
    void finish(){
        if(closed)return;
        try{if(joined)request(new JSONObject().put("action","leave"));}catch(Exception ignored){}
        closed=true;io.shutdown();for(CompletableFuture<JSONObject> f:pending.values())f.completeExceptionally(new IllegalStateException("Sessione chiusa"));
        try{ApiClient.post(endpoint,new JSONObject().put("action","leave").put("code",code).put("peer",peer).put("token",token));}catch(Exception ignored){}
        dispatch(()->{for(String id:new ArrayList<>(connections.keySet()))disposePeer(id);factory.dispose();rtc.shutdown();});
    }
}

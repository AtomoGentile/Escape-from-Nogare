package it.atomogentile.nogar;

import java.util.*;
import java.util.function.LongSupplier;
import org.json.*;

/** Single-room authority. Called only on the organizer's phone, never on the relay. */
final class PhoneGame {
    private static final class Player {
        final String peer,token=UUID.randomUUID().toString();
        final String name;String role;JSONObject location;long ping=-1,seen;
        Player(String peer,String name,String role,long now){this.peer=peer;this.name=name;this.role=role;seen=now;}
    }
    private final String code;
    private final LongSupplier clock;
    private final Map<String,Player> players=new LinkedHashMap<>();
    private JSONObject config,center,ended;
    private long started;
    PhoneGame(String code,JSONObject creation,LongSupplier clock) throws Exception {
        this.code=code;this.clock=clock;
        JSONObject c=creation.getJSONObject("config");
        double minutes=number(c,"minutes",2,120),diameter=number(c,"diameter",100,10000);
        double ping=number(c,"ping",5,300),shrink=number(c,"shrink",0,5),min=number(c,"minRadius",1,diameter/2);
        config=new JSONObject().put("minutes",minutes).put("diameter",diameter).put("ping",ping).put("shrink",shrink).put("minRadius",min);
        add("host",creation);
    }
    private static double number(JSONObject obj,String key,double low,double high) throws Exception {
        Object v=obj.get(key);if(!(v instanceof Number))throw new IllegalArgumentException("Valore non numerico: "+key);
        double n=((Number)v).doubleValue();if(!Double.isFinite(n)||n<low||n>high)throw new IllegalArgumentException("Valore fuori limite: "+key);return n;
    }
    private static String role(JSONObject b) throws Exception {
        String r=b.getString("role");if(!r.equals("prey")&&!r.equals("hunter"))throw new IllegalArgumentException("Squadra non valida");return r;
    }
    private void add(String peer,JSONObject b) throws Exception {
        if(started!=0||ended!=null)throw new IllegalArgumentException("Partita già iniziata");
        if(players.size()>=50)throw new IllegalArgumentException("Lobby piena");
        String name=b.getString("name").trim();if(name.isEmpty()||name.length()>24)throw new IllegalArgumentException("Nickname richiesto (massimo 24 caratteri)");
        for(Player p:players.values())if(p.name.equalsIgnoreCase(name))throw new IllegalArgumentException("Nickname già usato");
        players.put(peer,new Player(peer,name,role(b),clock.getAsLong()));
    }
    synchronized JSONObject handle(String peer,JSONObject body) {
        try {
            long now=clock.getAsLong();String action=body.getString("action");
            if(started!=0&&ended==null&&now-started>=config.getDouble("minutes")*60000)end("timeout",started+(long)(config.getDouble("minutes")*60000));
            Player p=players.get(peer);
            if(action.equals("join")&&p==null){add(peer,body);p=players.get(peer);}
            if(p==null)return new JSONObject().put("error","Non fai più parte della lobby").put("errorCode","AUTH_INVALID");
            p.seen=now;
            switch(action) {
                case "join": case "poll": break;
                case "location": {
                    JSONObject l=body.getJSONObject("location");double lat=number(l,"lat",-90,90),lng=number(l,"lng",-180,180),accuracy=number(l,"accuracy",0,1000);
                    if(ended==null&&(started==0||p.ping<0||now-p.ping>=config.getDouble("ping")*1000)){
                        p.location=new JSONObject().put("lat",lat).put("lng",lng).put("accuracy",accuracy).put("at",now);p.ping=now;
                    }break;
                }
                case "role":
                    if(started!=0||ended!=null)throw new IllegalArgumentException("Squadre bloccate dopo l’avvio");p.role=role(body);break;
                case "start": {
                    if(!peer.equals("host"))throw new IllegalArgumentException("Solo l’organizzatore può avviare");
                    if(started!=0||ended!=null)throw new IllegalArgumentException("Partita già iniziata o terminata");
                    boolean prey=false,hunter=false;for(Player q:players.values())if(now-q.seen<30000){prey|=q.role.equals("prey");hunter|=q.role.equals("hunter");}
                    if(!prey||!hunter)throw new IllegalArgumentException("Servono una preda e un cacciatore connessi");
                    if(p.location==null||now-p.location.getLong("at")>15000||p.location.getDouble("accuracy")>100)throw new IllegalArgumentException("Attendi un GPS recente e preciso (entro 100 m)");
                    center=new JSONObject().put("lat",p.location.getDouble("lat")).put("lng",p.location.getDouble("lng"));started=now;
                    for(Player q:players.values())q.ping=now;break;
                }
                case "end":
                    if(!peer.equals("host"))throw new IllegalArgumentException("Solo l’organizzatore può terminare");
                    if(started==0)throw new IllegalArgumentException("Partita non ancora iniziata");end("host",now);break;
                case "leave":
                    players.remove(peer);
                    if(peer.equals("host"))end("host_left",now);
                    else if(started!=0){boolean prey=false,hunter=false;for(Player q:players.values()){prey|=q.role.equals("prey");hunter|=q.role.equals("hunter");}if(!prey||!hunter)end("team_left",now);}
                    return new JSONObject().put("left",true);
                default:throw new IllegalArgumentException("Azione non valida");
            }
            return snapshot(p,now);
        } catch(Exception e) {
            try{return new JSONObject().put("error",e instanceof IllegalArgumentException?e.getMessage():"Richiesta non valida").put("errorCode","INVALID_REQUEST");}catch(JSONException impossible){throw new IllegalStateException(impossible);}
        }
    }
    private void end(String reason,long at) throws JSONException {
        if(ended==null)ended=new JSONObject().put("reason",reason).put("at",at);
        for(Player p:players.values())p.location=null;
    }
    private JSONObject snapshot(Player self,long now) throws Exception {
        double elapsed=started==0?0:Math.max(0,((ended==null?now:ended.getLong("at"))-started)/1000.0);
        JSONArray list=new JSONArray();for(Player p:players.values())list.put(new JSONObject().put("name",p.name).put("role",p.role).put("self",p==self).put("host",p.peer.equals("host")).put("online",now-p.seen<30000).put("location",p.location==null?JSONObject.NULL:p.location));
        return new JSONObject().put("code",code).put("token",self.token).put("host",self.peer.equals("host")).put("config",config)
            .put("started",started==0?JSONObject.NULL:started).put("ended",ended==null?JSONObject.NULL:ended).put("center",center==null?JSONObject.NULL:center)
            .put("serverTime",now).put("nextPingIn",started==0||ended!=null?0:Math.max(0,config.getDouble("ping")-(now-self.ping)/1000.0))
            .put("remaining",ended!=null?0:Math.max(0,config.getDouble("minutes")*60-elapsed)).put("radius",Math.max(config.getDouble("minRadius"),config.getDouble("diameter")/2-elapsed*config.getDouble("shrink"))).put("players",list);
    }
}

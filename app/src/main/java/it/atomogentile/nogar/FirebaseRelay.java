package it.atomogentile.nogar;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** REST adapter: anonymous Firebase identity, opaque peer mailboxes; no game logic. */
final class FirebaseRelay {
    private static final Map<String,FirebaseRelay> CLIENTS=new HashMap<>();
    static boolean isEndpoint(String endpoint){try{URI u=new URI(endpoint);return "https".equals(u.getScheme())&&firebaseHost(u.getHost())&&u.getPath().startsWith("/nogar-client/");}catch(Exception e){return false;}}
    static boolean firebaseHost(String host){return host!=null&&(host.endsWith(".firebaseio.com")||host.endsWith(".firebasedatabase.app"));}
    static String endpoint(String database,String key)throws Exception{
        URI u=new URI(database);if(!"https".equals(u.getScheme())||!firebaseHost(u.getHost())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/"))||!key.matches("[A-Za-z0-9_-]{20,200}"))throw new IllegalArgumentException("URL database o chiave API pubblica non validi");
        return "https://"+u.getHost()+"/nogar-client/"+key;
    }
    static synchronized FirebaseRelay client(String endpoint)throws Exception{
        FirebaseRelay existing=CLIENTS.get(endpoint);if(existing!=null)return existing;URI u=new URI(endpoint);
        FirebaseRelay c=new FirebaseRelay("https://"+u.getHost(),u.getPath().substring("/nogar-client/".length()),null,null,null);CLIENTS.put(endpoint,c);return c;
    }
    private final String database,key,authBase,tokenBase,namespace;
    private String uid,idToken,refreshToken;private long expires;
    private static final class Room {String code,owner,peer;long created,lastPulse;final Set<String> members=new HashSet<>();}
    private final Map<String,Room> rooms=new HashMap<>();
    // The alternate origins are used only by JVM integration tests against local emulators.
    FirebaseRelay(String database,String key,String authBase,String tokenBase,String namespace){this.database=database;this.key=key;this.authBase=authBase==null?"https://identitytoolkit.googleapis.com/v1":authBase;this.tokenBase=tokenBase==null?"https://securetoken.googleapis.com/v1":tokenBase;this.namespace=namespace;}
    private void auth()throws Exception{
        if(idToken!=null&&System.nanoTime()<expires)return;
        JSONObject result;
        if(refreshToken==null){result=(JSONObject)http("POST",authBase+"/accounts:signUp?key="+enc(key),new JSONObject().put("returnSecureToken",true),false);uid=result.getString("localId");idToken=result.getString("idToken");refreshToken=result.getString("refreshToken");expires=System.nanoTime()+(result.getLong("expiresIn")-60)*1000000000L;}
        else{result=(JSONObject)http("POST",tokenBase+"/token?key="+enc(key),"grant_type=refresh_token&refresh_token="+enc(refreshToken),true);idToken=result.getString("id_token");refreshToken=result.getString("refresh_token");expires=System.nanoTime()+(result.getLong("expires_in")-60)*1000000000L;}
    }
    synchronized void health()throws Exception{auth();db("GET","nogar/v1/ping",null);}
    private static JSONObject timestamp()throws JSONException{return new JSONObject().put(".sv","timestamp");}
    private Object db(String method,String path,Object body)throws Exception{
        auth();return http(method,database+"/"+path+".json?auth="+enc(idToken)+(namespace==null?"":"&ns="+enc(namespace)),body,false);
    }
    synchronized JSONObject request(JSONObject b)throws Exception{
        auth();String action=b.getString("action");
        if(action.equals("create")){
            String proof=b.getString("admission");if(!proof.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invito non valido");
            for(int tries=0;tries<8;tries++){
                String code=String.valueOf(100000+new java.security.SecureRandom().nextInt(900000));String path="nogar/v1/rooms/"+code;
                JSONObject member=new JSONObject().put("proof",proof).put("joinedAt",timestamp());
                JSONObject room=new JSONObject().put("owner",uid).put("proof",proof).put("createdAt",timestamp()).put("pulse",timestamp()).put("members",new JSONObject().put(uid,member));
                try{db("PUT",path,room);}catch(IOException denied){if(tries==7)throw denied;continue;}
                Room state=new Room();state.code=code;state.owner=uid;state.peer="host";state.created=((Number)db("GET",path+"/createdAt",null)).longValue();state.lastPulse=System.currentTimeMillis();state.members.add(uid);rooms.put(code,state);
                return bootstrap(state);
            }
        }
        String code=b.getString("code");if(!code.matches("[0-9]{6}"))throw new IllegalArgumentException("Codice non valido");String path="nogar/v1/rooms/"+code;
        if(action.equals("join")){
            db("PUT",path+"/members/"+uid,new JSONObject().put("proof",b.getString("admission")).put("joinedAt",timestamp()));
            Room state=new Room();state.code=code;state.owner=(String)db("GET",path+"/owner",null);state.peer=uid;state.created=((Number)db("GET",path+"/createdAt",null)).longValue();state.lastPulse=0;rooms.put(code,state);return bootstrap(state);
        }
        Room room=rooms.get(code);if(room==null)return error("Partita non disponibile","ROOM_GONE");
        if(!room.peer.equals(b.getString("peer"))||!uid.equals(b.getString("token")))return error("Accesso non valido","AUTH_INVALID");
        if(action.equals("leave")){
            try{
                if(!room.peer.equals("host"))db("DELETE",path+"/inbox/"+uid,null);
                db("DELETE",path+(room.peer.equals("host")?"":"/members/"+uid),null);
            }finally{
                rooms.remove(code);
                if(rooms.isEmpty()){
                    try{http("POST",authBase+"/accounts:delete?key="+enc(key),new JSONObject().put("idToken",idToken),false);}catch(Exception ignored){}
                    uid=null;idToken=null;refreshToken=null;expires=0;
                }
            }
            return new JSONObject().put("left",true);
        }
        if(!action.equals("exchange"))throw new IllegalArgumentException("Azione non valida");
        long now=System.currentTimeMillis();
        if(now-room.lastPulse>20000){
            try{
                if(room.peer.equals("host"))db("PUT",path+"/pulse",timestamp());
                else {long pulse=((Number)db("GET",path+"/pulse",null)).longValue();if(now-pulse>120000)return error("Organizzatore disconnesso","ROOM_GONE");}
            }catch(Exception e){return error("Collegamento Firebase perso. Controlla database e regole.","ROOM_GONE");}
            room.lastPulse=now;
        }
        JSONArray events=new JSONArray();
        if(room.peer.equals("host")){
            Object raw=db("GET",path+"/members",null);if(!(raw instanceof JSONObject))return error("Partita terminata","ROOM_GONE");JSONObject members=(JSONObject)raw;Set<String> current=new HashSet<>();Iterator<String> it=members.keys();while(it.hasNext())current.add(it.next());
            for(String member:current)if(!room.members.contains(member)&&!member.equals(uid))events.put(new JSONObject().put("kind","peer").put("from",member));
            for(String member:room.members)if(!current.contains(member)&&!member.equals(uid))events.put(new JSONObject().put("kind","left").put("from",member));room.members.clear();room.members.addAll(current);
        }
        JSONArray packets=b.getJSONArray("packets");JSONObject updates=new JSONObject();
        for(int i=0;i<packets.length();i++){
            JSONObject p=packets.getJSONObject(i);String to=p.getString("to");String target=to.equals("host")?room.owner:to;
            if(!room.peer.equals("host")&&!target.equals(room.owner))throw new IllegalArgumentException("Destinazione non valida");
            if(room.peer.equals("host")&&!room.members.contains(target))continue;
            if(!p.has("firebaseId"))p.put("firebaseId",UUID.randomUUID().toString().replace("-",""));
            updates.put(target+"/"+uid+"/"+p.getString("firebaseId"),new JSONObject().put("packet",p.getString("packet")).put("createdAt",timestamp()));
        }
        if(updates.length()>0)db("PATCH",path+"/inbox",updates);
        Object raw=db("GET",path+"/inbox/"+uid,null);JSONObject ack=new JSONObject();
        if(raw instanceof JSONObject){JSONObject senders=(JSONObject)raw;Iterator<String> froms=senders.keys();while(froms.hasNext()){
            String sender=froms.next();JSONObject messages=senders.getJSONObject(sender);Iterator<String> ids=messages.keys();while(ids.hasNext()){
                String id=ids.next();JSONObject message=messages.getJSONObject(id);events.put(new JSONObject().put("kind","message").put("from",sender.equals(room.owner)?"host":sender).put("packet",message.getString("packet")));ack.put(sender+"/"+id,JSONObject.NULL);
            }
        }}
        if(ack.length()>0){try{db("PATCH",path+"/inbox/"+uid,ack);}catch(Exception ignored){/* Replay protection handles a repeated delivery. */}}
        return new JSONObject().put("events",events).put("cursor",b.getLong("cursor")+1);
    }
    private JSONObject bootstrap(Room room)throws JSONException{return new JSONObject().put("code",room.code).put("peer",room.peer).put("token",uid);}
    private static JSONObject error(String message,String code)throws JSONException{return new JSONObject().put("error",message).put("errorCode",code);}
    private static String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private static Object http(String method,String url,Object body,boolean form)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestMethod(method.equals("PATCH")?"POST":method);if(method.equals("PATCH"))c.setRequestProperty("X-HTTP-Method-Override","PATCH");
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",form?"application/x-www-form-urlencoded":"application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();InputStream stream=status<400?c.getInputStream():c.getErrorStream();if(stream==null)throw new IOException("Firebase HTTP "+status);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream in=stream){byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1){buffer.write(chunk,0,n);if(buffer.size()>2097152)throw new IOException("Firebase response too large");}}
            if(status>=400)throw new IOException("Firebase HTTP "+status+": verifica chiave API, accesso anonimo e regole");
            return new JSONTokener(buffer.toString("UTF-8")).nextValue();
        }finally{c.disconnect();}
    }
}

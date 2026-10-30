package it.atomogentile.nogar;

import org.json.*;

/** The configured PC owns state; mobile clients exchange authenticated HTTPS requests. */
final class PcSession implements GameSession {
    interface Requester { JSONObject post(JSONObject body)throws Exception; }
    private final Requester server;
    private final String code,token,invitation;
    private final JSONObject initial;
    private boolean closed;
    static PcSession open(String endpoint,JSONObject creation)throws Exception{
        return open(endpoint,creation,body->ApiClient.post(endpoint,body));
    }
    // Injectable transport exercises the real PC backend locally without weakening app TLS.
    static PcSession open(String endpoint,JSONObject creation,Requester server)throws Exception{
        JSONObject body=new JSONObject(creation.toString());body.remove("invite");
        final byte[] key;
        if(body.getString("action").equals("create"))key=PeerCrypto.newKey();
        else {
            PeerCrypto.Invitation invite=new PeerCrypto.Invitation(creation.getString("invite"));
            if(!invite.relay.equals(endpoint))throw new IllegalArgumentException("Collega prima il server indicato nell’invito");
            key=invite.key;body.put("code",invite.code);
        }
        body.put("joinProof",PeerCrypto.admission(key));
        JSONObject result=server.post(body);
        if(result.has("error"))throw new IllegalArgumentException(result.getString("error"));
        return new PcSession(server,endpoint,key,result);
    }
    private PcSession(Requester server,String endpoint,byte[] key,JSONObject initial)throws Exception{
        this.server=server;this.initial=new JSONObject(initial.toString());code=initial.getString("code");token=initial.getString("token");invitation=PeerCrypto.invite(endpoint,code,key);
    }
    private JSONObject decorated(JSONObject result)throws JSONException{
        if(!result.has("error")&&!result.optBoolean("left"))result.put("authority","pc").put("transport","pc").put("invitation",invitation);
        return result;
    }
    @Override public synchronized JSONObject request(JSONObject request)throws Exception{
        if(closed)throw new IllegalStateException("Sessione chiusa");
        if(request.getString("action").equals("join"))return decorated(new JSONObject(initial.toString()));
        JSONObject body=new JSONObject(request.toString());body.remove("invite");body.remove("joinProof");
        body.put("code",code).put("token",token);
        return decorated(server.post(body));
    }
    @Override public String invitation(){return invitation;}
    @Override public String code(){return code;}
    @Override public boolean ownsAuthority(){return false;}
    @Override public synchronized void finish(){
        if(closed)return;closed=true;
        try{server.post(new JSONObject().put("action","leave").put("code",code).put("token",token));}catch(Exception ignored){}
    }
}

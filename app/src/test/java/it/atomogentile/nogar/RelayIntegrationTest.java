package it.atomogentile.nogar;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.net.ServerSocket;

/** Uses the actual Node relay, actual JVM authority and actual AES-GCM wire format. */
public class RelayIntegrationTest {
    private JSONObject auth(JSONObject who,String action)throws Exception{return new JSONObject().put("action",action).put("code",who.getString("code")).put("peer",who.getString("peer")).put("token",who.getString("token"));}
    private JSONObject exchange(String endpoint,JSONObject who,String to,String packet,long cursor)throws Exception{
        JSONArray packets=new JSONArray();if(packet!=null)packets.put(new JSONObject().put("to",to).put("packet",packet));
        return ApiClient.post(endpoint,auth(who,"exchange").put("cursor",cursor).put("packets",packets));
    }
    @Test(timeout=30000) public void encryptedRequestTraversesRealRelayAndRunsOnlyOnHost()throws Exception{
        int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        ProcessBuilder builder=new ProcessBuilder("node",new File(System.getProperty("nogar.repository"),"server/relay.mjs").getAbsolutePath());
        builder.environment().put("HOST","127.0.0.1");builder.environment().put("PORT",String.valueOf(port));
        builder.redirectOutput(new File("/dev/null"));builder.redirectError(new File("/dev/null"));
        Process server=builder.start();String endpoint="http://127.0.0.1:"+port;
        try{
            boolean ready=false;for(int i=0;i<60;i++){try{ApiClient.health(endpoint);ready=true;break;}catch(Exception e){if(!server.isAlive())throw new IOException("Relay exited");Thread.sleep(50);}}
            assertTrue("Local relay failed to start",ready);
            byte[] key=PeerCrypto.newKey();String proof=PeerCrypto.admission(key);
            JSONObject host=ApiClient.post(endpoint,new JSONObject().put("action","create").put("admission",proof));
            JSONObject guest=ApiClient.post(endpoint,new JSONObject().put("action","join").put("code",host.getString("code")).put("admission",proof));
            String guestId=guest.getString("peer");PeerCrypto crypto=new PeerCrypto(key,host.getString("code"));
            PhoneGame game=new PhoneGame(host.getString("code"),new JSONObject().put("name","Host").put("role","hunter").put("config",new JSONObject().put("minutes",20).put("diameter",1200).put("minRadius",200).put("ping",30).put("shrink",.4)),()->1700000000000L);
            JSONObject join=new JSONObject().put("action","join").put("name","Ghost").put("role","prey");
            String packet=crypto.seal(guestId,"host",new JSONObject().put("seq",1).put("body",join));
            exchange(endpoint,guest,"host",packet,0);JSONObject hostInbox=exchange(endpoint,host,null,null,0);
            JSONObject envelope=hostInbox.getJSONArray("events").getJSONObject(1);
            JSONObject result=game.handle(envelope.getString("from"),crypto.open(guestId,"host",envelope.getString("packet")).getJSONObject("body"));
            assertEquals(2,result.getJSONArray("players").length());
            packet=crypto.seal("host",guestId,new JSONObject().put("seq",1).put("body",result));
            exchange(endpoint,host,guestId,packet,hostInbox.getLong("cursor"));
            JSONObject guestInbox=exchange(endpoint,guest,null,null,0);
            JSONObject received=crypto.open("host",guestId,guestInbox.getJSONArray("events").getJSONObject(0).getString("packet")).getJSONObject("body");
            assertEquals(200,received.getJSONObject("config").getInt("minRadius"));assertFalse(received.getBoolean("host"));
            String outer=guestInbox.toString();assertFalse(outer.contains("Ghost"));assertFalse(outer.contains("minRadius"));assertFalse(outer.contains(PeerCrypto.B64.encodeToString(key)));
        }finally{server.destroyForcibly();server.waitFor();}
    }
}

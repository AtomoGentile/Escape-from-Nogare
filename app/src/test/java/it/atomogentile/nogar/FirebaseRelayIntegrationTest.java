package it.atomogentile.nogar;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;
import org.json.*;

/** Real Firebase Auth + Database emulators. Skips locally unless both are running. */
public class FirebaseRelayIntegrationTest {
    private JSONObject call(FirebaseRelay c,JSONObject who,String action)throws Exception{return c.request(new JSONObject(who.toString()).put("action",action));}
    private JSONObject exchange(FirebaseRelay c,JSONObject who,String to,String packet,long cursor)throws Exception{
        JSONArray packets=new JSONArray();if(packet!=null)packets.put(new JSONObject().put("to",to).put("packet",packet));
        return c.request(new JSONObject(who.toString()).put("action","exchange").put("cursor",cursor).put("packets",packets));
    }
    @Test(timeout=60000) public void actualAuthAndDatabaseRouteCiphertextAndCleanUp()throws Exception{
        String database=System.getenv("FIREBASE_DATABASE_EMULATOR_HOST"),auth=System.getenv("FIREBASE_AUTH_EMULATOR_HOST");
        Assume.assumeTrue(database!=null&&auth!=null);
        String identity="http://"+auth+"/identitytoolkit.googleapis.com/v1",refresh="http://"+auth+"/securetoken.googleapis.com/v1";
        FirebaseRelay host=new FirebaseRelay("http://"+database,"demo-api-key",identity,refresh,"demo-nogar");
        FirebaseRelay guest=new FirebaseRelay("http://"+database,"demo-api-key",identity,refresh,"demo-nogar");
        host.health();guest.health();byte[] key=PeerCrypto.newKey();String proof=PeerCrypto.admission(key);
        JSONObject owner=host.request(new JSONObject().put("action","create").put("admission",proof));
        JSONObject client=guest.request(new JSONObject().put("action","join").put("admission",proof).put("code",owner.getString("code")));
        PeerCrypto crypto=new PeerCrypto(key,owner.getString("code"));String guestId=client.getString("peer");
        java.lang.reflect.Field expires=FirebaseRelay.class.getDeclaredField("expires");expires.setAccessible(true);expires.setLong(host,Long.MIN_VALUE);host.health();
        String packet=crypto.seal(guestId,"host",new JSONObject().put("seq",1).put("lat",45.123456));
        exchange(guest,client,"host",packet,0);JSONObject inbox=exchange(host,owner,null,null,0);
        assertEquals("peer",inbox.getJSONArray("events").getJSONObject(0).getString("kind"));
        JSONObject event=inbox.getJSONArray("events").getJSONObject(1);assertEquals(guestId,event.getString("from"));
        assertEquals(45.123456,crypto.open(guestId,"host",event.getString("packet")).getDouble("lat"),.0000001);
        assertFalse(inbox.toString().contains("45.123456"));
        assertEquals(0,exchange(host,owner,null,null,1).getJSONArray("events").length());
        packet=crypto.seal("host",guestId,new JSONObject().put("seq",1).put("reply",true));
        exchange(host,owner,guestId,packet,2);JSONObject received=exchange(guest,client,null,null,1);
        assertTrue(crypto.open("host",guestId,received.getJSONArray("events").getJSONObject(0).getString("packet")).getBoolean("reply"));
        call(guest,client,"leave");assertEquals("left",exchange(host,owner,null,null,3).getJSONArray("events").getJSONObject(0).getString("kind"));
        // A new session gets a new transport UID, preserving sequence and identity separation.
        JSONObject rejoined=guest.request(new JSONObject().put("action","join").put("admission",proof).put("code",owner.getString("code")));
        assertNotEquals(guestId,rejoined.getString("peer"));call(guest,rejoined,"leave");call(host,owner,"leave");
    }
}

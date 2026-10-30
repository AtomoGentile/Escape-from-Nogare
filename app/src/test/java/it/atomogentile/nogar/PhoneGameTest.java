package it.atomogentile.nogar;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.concurrent.atomic.AtomicLong;
public class PhoneGameTest {
    private JSONObject creation()throws Exception{return new JSONObject().put("name","Host").put("role","hunter").put("config",new JSONObject().put("minutes",20).put("diameter",1200).put("ping",30).put("shrink",.4).put("minRadius",200));}
    private JSONObject action(String action)throws Exception{return new JSONObject().put("action",action);}
    private JSONObject location(double lat)throws Exception{return action("location").put("location",new JSONObject().put("lat",lat).put("lng",12).put("accuracy",5));}
    @Test public void phoneIsAuthorityAndMinimumIsRespected()throws Exception{
        AtomicLong now=new AtomicLong(1700000000000L);PhoneGame g=new PhoneGame("123456",creation(),now::get);
        assertTrue(g.handle("host",action("start")).has("error"));
        assertFalse(g.handle("peer",action("join").put("name","Ghost").put("role","prey")).has("error"));
        assertTrue(g.handle("peer",action("start")).has("error"));
        g.handle("host",location(45));g.handle("peer",location(45));
        JSONObject started=g.handle("host",action("start"));assertFalse(started.has("error"));assertEquals(30,started.getDouble("nextPingIn"),.001);
        JSONObject early=g.handle("peer",location(46));assertEquals(45,early.getJSONArray("players").getJSONObject(1).getJSONObject("location").getDouble("lat"),.001);
        now.addAndGet(30000);JSONObject ping=g.handle("peer",location(46));assertEquals(46,ping.getJSONArray("players").getJSONObject(1).getJSONObject("location").getDouble("lat"),.001);
        now.addAndGet(1070000);JSONObject floor=g.handle("host",action("poll"));assertEquals(200,floor.getDouble("radius"),.001);assertEquals(100,floor.getDouble("remaining"),.001);
        now.addAndGet(100000);JSONObject ended=g.handle("peer",action("poll"));assertEquals("timeout",ended.getJSONObject("ended").getString("reason"));assertTrue(ended.getJSONArray("players").getJSONObject(0).isNull("location"));
    }
    @Test public void identityComesFromPeerTransportNotPayload()throws Exception{
        PhoneGame g=new PhoneGame("123456",creation(),()->1700000000000L);
        assertEquals("AUTH_INVALID",g.handle("stranger",action("poll").put("token","host")).getString("errorCode"));
        g.handle("guest",action("join").put("name","Ghost").put("role","prey"));
        assertTrue(g.handle("guest",action("end").put("peer","host")).has("error"));
        g.handle("host",location(45));g.handle("host",action("start"));
        assertTrue(g.handle("late",action("join").put("name","Late").put("role","prey")).has("error"));
        g.handle("host",action("leave"));assertEquals("host_left",g.handle("guest",action("poll")).getJSONObject("ended").getString("reason"));
    }
    @Test public void invalidMinimumCannotCreateMatch()throws Exception{
        JSONObject b=creation();b.getJSONObject("config").put("minRadius",601);
        try{new PhoneGame("123456",b,()->1L);fail();}catch(IllegalArgumentException expected){}
    }
}

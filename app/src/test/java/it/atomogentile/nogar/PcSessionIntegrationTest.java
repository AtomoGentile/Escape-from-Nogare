package it.atomogentile.nogar;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.net.ServerSocket;

/** Real Java client and real PC engine; only the HTTPS transport is replaced with loopback. */
public class PcSessionIntegrationTest {
    private JSONObject action(String value)throws Exception{return new JSONObject().put("action",value);}
    private JSONObject join(String invite,String name,String role)throws Exception{return action("join").put("invite",invite).put("name",name).put("role",role);}
    @Test(timeout=30000) public void pcKeepsMatchAfterCreatorLeavesAndProtectsAdmission()throws Exception{
        int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        ProcessBuilder builder=new ProcessBuilder("node",new File(System.getProperty("nogar.repository"),"server/server.mjs").getAbsolutePath());
        builder.environment().put("HOST","127.0.0.1");builder.environment().put("PORT",String.valueOf(port));
        builder.redirectOutput(new File("/dev/null"));builder.redirectError(new File("/dev/null"));
        Process server=builder.start();String local="http://127.0.0.1:"+port, endpoint="https://pc.example.test";
        try{
            boolean ready=false;for(int i=0;i<60;i++){try{assertEquals("pc",ApiClient.kind(local));ready=true;break;}catch(IOException e){if(!server.isAlive())throw new IOException("PC server exited");Thread.sleep(50);}}
            assertTrue(ready);PcSession.Requester transport=body->ApiClient.post(local,body);
            PcSession host=PcSession.open(endpoint,action("create").put("name","Host").put("role","hunter").put("config",new JSONObject().put("minutes",20).put("diameter",1200).put("minRadius",200).put("ping",30).put("shrink",.4)),transport);
            PcSession prey=PcSession.open(endpoint,join(host.invitation(),"Ghost","prey"),transport);
            PcSession hunter=PcSession.open(endpoint,join(host.invitation(),"Hunter","hunter"),transport);
            assertFalse(host.ownsAuthority());
            assertEquals(200,prey.request(action("join")).getJSONObject("config").getInt("minRadius"));
            assertTrue(prey.request(action("start")).has("error"));
            JSONObject bad=transport.post(action("join").put("code",host.code()).put("name","Intruder").put("role","prey").put("joinProof","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
            assertEquals("INVITE_INVALID",bad.getString("errorCode"));
            host.request(action("location").put("location",new JSONObject().put("lat",45).put("lng",12).put("accuracy",4)));
            assertFalse(host.request(action("start")).has("error"));host.finish();
            JSONObject result=prey.request(action("poll"));
            assertTrue(result.isNull("ended"));assertTrue(result.getBoolean("host"));assertEquals("pc",result.getString("authority"));assertEquals(2,result.getJSONArray("players").length());
            assertTrue(hunter.request(action("end")).has("error"));
            result=prey.request(action("end"));assertEquals("host",result.getJSONObject("ended").getString("reason"));
            for(int i=0;i<result.getJSONArray("players").length();i++)assertTrue(result.getJSONArray("players").getJSONObject(i).isNull("location"));
            prey.finish();hunter.finish();
        }finally{server.destroyForcibly();server.waitFor();}
    }
}

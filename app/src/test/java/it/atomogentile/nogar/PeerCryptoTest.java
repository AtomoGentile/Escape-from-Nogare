package it.atomogentile.nogar;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
public class PeerCryptoTest {
    @Test public void encryptionRoundTripAndTamperProtection()throws Exception{
        byte[] key=PeerCrypto.newKey();PeerCrypto a=new PeerCrypto(key,"123456"),b=new PeerCrypto(key,"123456");
        JSONObject message=new JSONObject().put("lat",45.123456).put("kind","request").put("seq",1);
        String packet=a.seal("peer","host",message);
        assertFalse(packet.contains("45.123456"));assertEquals(45.123456,b.open("peer","host",packet).getDouble("lat"),.0000001);
        byte[] bytes=PeerCrypto.DEC.decode(packet);bytes[15]^=1;
        try{b.open("peer","host",PeerCrypto.B64.encodeToString(bytes));fail();}catch(javax.crypto.AEADBadTagException expected){}
        try{b.open("impostor","host",packet);fail();}catch(javax.crypto.AEADBadTagException expected){}
        try{new PeerCrypto(PeerCrypto.newKey(),"123456").open("peer","host",packet);fail();}catch(javax.crypto.AEADBadTagException expected){}
        try{new PeerCrypto(key,"654321").open("peer","host",packet);fail();}catch(javax.crypto.AEADBadTagException expected){}
    }
    @Test public void inviteKeepsKeyInFragmentAndAdmissionIsNotKey()throws Exception{
        byte[] key=PeerCrypto.newKey();String invite=PeerCrypto.invite("https://relay.example.test","123456",key);
        PeerCrypto.Invitation read=new PeerCrypto.Invitation(invite);assertArrayEquals(key,read.key);assertEquals("123456",read.code);
        assertEquals(64,PeerCrypto.admission(key).length());assertNotEquals(PeerCrypto.B64.encodeToString(key),PeerCrypto.admission(key));
        assertEquals("join",new java.net.URI(invite).getHost());assertNull(new java.net.URI(invite).getQuery());
    }
    @Test public void httpRelayIsRejected()throws Exception{
        String invite=PeerCrypto.invite("http://relay.example.test","123456",PeerCrypto.newKey());
        try{new PeerCrypto.Invitation(invite);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void replayWindowAllowsReorderingButRejectsReplays(){
        PeerCrypto.ReplayWindow window=new PeerCrypto.ReplayWindow();
        assertTrue(window.accept(2));assertTrue(window.accept(1));assertFalse(window.accept(2));
        assertFalse(window.accept(0));assertTrue(window.accept(1000));assertFalse(window.accept(1));
        assertTrue(window.accept(999));assertFalse(window.accept(999));
    }
}

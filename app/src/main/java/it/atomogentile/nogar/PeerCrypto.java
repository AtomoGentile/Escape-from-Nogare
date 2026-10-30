package it.atomogentile.nogar;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** The invitation key remains on participants' phones, never in the relay request. */
final class PeerCrypto {
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private final byte[] key;
    private final String code;
    private final SecureRandom random = new SecureRandom();
    PeerCrypto(byte[] key, String code) {
        if (key.length != 32 || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid key or room");
        this.key = key.clone(); this.code = code;
    }
    static byte[] newKey() { byte[] k=new byte[32];new SecureRandom().nextBytes(k);return k; }
    static String admission(byte[] key) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update("nogar-admission-v1".getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:hash.digest(key))hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    static String invite(String relay,String code,byte[] key) throws Exception {
        return "nogar://join#"+B64.encodeToString(new JSONObject().put("v",1).put("u",relay).put("c",code).put("k",B64.encodeToString(key)).toString().getBytes(StandardCharsets.UTF_8));
    }
    static final class Invitation {
        final String relay,code;final byte[] key;
        Invitation(String input) throws Exception {
            if(input.length()>4096)throw new IllegalArgumentException("Invite too large");
            URI uri=new URI(input.trim());if(!"nogar".equals(uri.getScheme())||!"join".equals(uri.getHost())||uri.getFragment()==null)throw new IllegalArgumentException("Invalid invitation");
            JSONObject obj=new JSONObject(new String(DEC.decode(uri.getFragment()),StandardCharsets.UTF_8));
            if(obj.getInt("v")!=1)throw new IllegalArgumentException("Unsupported invitation");
            relay=obj.getString("u");URI url=new URI(relay);
            if(!"https".equals(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null)throw new IllegalArgumentException("HTTPS relay required");
            code=obj.getString("c");key=DEC.decode(obj.getString("k"));new PeerCrypto(key,code);
        }
    }
    String seal(String from,String to,JSONObject message) throws Exception {
        byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(aad(from,to));
        byte[] ciphertext=c.doFinal(message.toString().getBytes(StandardCharsets.UTF_8));byte[] packet=new byte[nonce.length+ciphertext.length];
        System.arraycopy(nonce,0,packet,0,12);System.arraycopy(ciphertext,0,packet,12,ciphertext.length);return B64.encodeToString(packet);
    }
    JSONObject open(String from,String to,String packet) throws Exception {
        if(packet.length()>90000)throw new IllegalArgumentException("Packet too large");byte[] data=DEC.decode(packet);if(data.length<28)throw new IllegalArgumentException("Invalid packet");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(data,12)));c.updateAAD(aad(from,to));
        return new JSONObject(new String(c.doFinal(data,12,data.length-12),StandardCharsets.UTF_8));
    }
    static final class ReplayWindow {
        private long high;
        private final Set<Long> seen=new HashSet<>();
        synchronized boolean accept(long seq){
            if(seq<1||seq<=high-512||!seen.add(seq))return false;
            high=Math.max(high,seq);seen.removeIf(value->value<=high-512);return true;
        }
    }
    private byte[] aad(String from,String to) {return ("NOGAR1\n"+code+"\n"+from+"\n"+to).getBytes(StandardCharsets.UTF_8);}
}

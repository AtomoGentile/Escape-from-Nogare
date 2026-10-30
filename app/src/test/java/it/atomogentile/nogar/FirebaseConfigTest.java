package it.atomogentile.nogar;
import org.junit.Test;
import static org.junit.Assert.*;
public class FirebaseConfigTest {
    @Test public void configAllowsOnlyHttpsFirebaseRoots()throws Exception{
        String key="fake-public-project-key-12345";
        String endpoint=FirebaseRelay.endpoint("https://demo-default-rtdb.europe-west1.firebasedatabase.app/",key);
        assertTrue(FirebaseRelay.isEndpoint(endpoint));assertTrue(endpoint.endsWith("/nogar-client/"+key));
        assertFalse(FirebaseRelay.isEndpoint("https://example.test/nogar-client/"+key));
        for(String url:new String[]{"http://demo.firebaseio.com","https://example.test","https://demo.firebaseio.com/health","https://user:pass@demo.firebaseio.com","https://demo.firebaseio.com?auth=secret"}){
            try{FirebaseRelay.endpoint(url,key);fail(url);}catch(IllegalArgumentException expected){}
        }
        try{FirebaseRelay.endpoint("https://demo.firebaseio.com","");fail();}catch(IllegalArgumentException expected){}
    }
}

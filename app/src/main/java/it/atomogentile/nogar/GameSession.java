package it.atomogentile.nogar;

import android.content.Context;
import org.json.JSONObject;

/** Shared lifecycle for an authoritative PC or a phone with encrypted transport. */
interface GameSession {
    JSONObject request(JSONObject body) throws Exception;
    String invitation();
    String code();
    boolean ownsAuthority();
    void finish();

    static GameSession open(Context context,String endpoint,String expectedKind,JSONObject creation)throws Exception{
        String kind=ApiClient.kind(endpoint);
        if(expectedKind!=null&&!expectedKind.isEmpty()&&!expectedKind.equals(kind))
            throw new IllegalArgumentException("Il tipo di servizio e cambiato: collegalo di nuovo prima di giocare");
        return kind.equals("pc")?PcSession.open(endpoint,creation):PhoneSession.open(context,endpoint,creation);
    }
}

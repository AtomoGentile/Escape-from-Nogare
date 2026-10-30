package it.atomogentile.nogar;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.webkit.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.WindowInsets;
import android.graphics.Insets;
import org.json.*;
import java.net.URI;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private volatile String endpoint = "";
    private String pendingPhoneRequest;
    private final java.util.concurrent.atomic.AtomicBoolean connecting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private boolean visible, ready;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        endpoint = getPreferences(0).getString("server", "");
        web = new WebView(this);
        setContentView(web);
        if (Build.VERSION.SDK_INT >= 35) {
            web.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        web.setBackgroundColor(0xff090d18);
        web.getSettings().setJavaScriptEnabled(true); // Bundled UI only; remote navigation is blocked.
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " NogarHunt/0.8.0 (+https://github.com/AtomoGentile/Escape-from-Nogare)");
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.addJavascriptInterface(this, "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) { ready = true; restoreMatch(); handleInvite(getIntent()); }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    void js(String script) {
        runOnUiThread(() -> { if (ready && !isDestroyed()) web.evaluateJavascript(script, null); });
    }

    private void restoreMatch() {
        String state = TrackingService.snapshot;
        if (state != null) js("accepting=true;receive(" + state + ")");
        TrackingService tracking = TrackingService.current;
        if (tracking != null) tracking.emitFix();
    }

    @JavascriptInterface public void requestGps() {
        runOnUiThread(() -> {
            if (!visible) return;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 42);
            } else requestNotifications();
        });
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(0).getBoolean("notificationAsked", false)) {
            getPreferences(0).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
    }

    @JavascriptInterface public String savedServer() {
        String saved=getPreferences(0).getString("server", "");
        if(FirebaseRelay.isEndpoint(saved)){try{return "https://"+new URI(saved).getHost();}catch(Exception ignored){}}
        return saved;
    }
    @JavascriptInterface public String savedFirebaseKey(){
        String saved=getPreferences(0).getString("server", "");
        if(FirebaseRelay.isEndpoint(saved)){try{return new URI(saved).getPath().substring("/nogar-client/".length());}catch(Exception ignored){}}
        return "";
    }

    @JavascriptInterface public void connect(String url,String publicApiKey) {
        if (TrackingService.current != null) { js("notice('Esci dalla lobby prima di cambiare server')"); return; }
        final String target;
        try {
            URI u = new URI(url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null) throw new Exception();
            target = FirebaseRelay.firebaseHost(u.getHost()) ? FirebaseRelay.endpoint(url,publicApiKey.trim()) : url.replaceAll("/+$", "");
        } catch (Exception e) {
            js("connectionFailed('Controlla URL HTTPS del database Firebase e chiave API pubblica, oppure l’indirizzo del tuo relay')"); return;
        }
        if (!connecting.compareAndSet(false, true)) return;
        js("connecting()");
        net.execute(() -> {
            try {
                ApiClient.health(target);
                endpoint = target;
                getPreferences(0).edit().putString("server", endpoint).apply();
                js("connected()");
            } catch (Exception e) {
                js("connectionFailed('Connessione non riuscita. Per Firebase controlla URL, chiave API, accesso anonimo e regole; per un relay verifica /health.')");
            } finally { connecting.set(false); }
        });
    }

    @JavascriptInterface public void openServerGuide() {
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://github.com/AtomoGentile/Escape-from-Nogare/blob/main/docs/FIREBASE.md")));
            } catch (ActivityNotFoundException e) { js("notice('Apri docs/FIREBASE.md nel repository GitHub Escape-from-Nogare')"); }
        });
    }

    @JavascriptInterface public void api(String body) {
        try {
            JSONObject request=new JSONObject(body);String action=request.getString("action");
            if((action.equals("create")||action.equals("join"))&&TrackingService.current==null){
                runOnUiThread(()->{
                    pendingPhoneRequest=body;
                    if(!visible)return;
                    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestGps();return;}
                    startPhoneRequest();
                });return;
            }
            net.execute(()->{try{
                TrackingService active=TrackingService.current;
                if(active==null)throw new IllegalStateException("Sessione interrotta");
                JSONObject result=active.command(request);
                if(TrackingService.current==active)js("receive("+result+")");
            }catch(Exception e){js("networkError()");}});
        }catch(Exception e){js("networkError()");}
    }
    private void startPhoneRequest(){
        if(!visible||pendingPhoneRequest==null)return;
        try {
            startForegroundService(new Intent(this,TrackingService.class).putExtra("endpoint",endpoint).putExtra("phoneRequest",pendingPhoneRequest));
            pendingPhoneRequest=null;
        }catch(Exception e){pendingPhoneRequest=null;js("setBusy(false);connectionFailed('Impossibile avviare il telefono organizzatore. Controlla i permessi GPS.')");}
    }
    @JavascriptInterface public void startTracking(String ignored) { /* Native session already owns foreground GPS. */ }
    @JavascriptInterface public void pasteInvite(){
        runOnUiThread(()->{try{
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(!clipboard.hasPrimaryClip()||clipboard.getPrimaryClip().getItemCount()==0)throw new IllegalArgumentException();
            acceptInvite(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());
        }catch(Exception e){js("notice('Copia prima l’invito completo ricevuto dall’organizzatore')");}});
    }
    private void acceptInvite(String text)throws Exception {
        if(TrackingService.current!=null){js("notice('Esci dalla partita prima di aprire un altro invito')");return;}
        PeerCrypto.Invitation invite=new PeerCrypto.Invitation(text);
        js("prefillInvite("+JSONObject.quote(text.trim())+","+JSONObject.quote(invite.relay)+")");
    }
    private void handleInvite(Intent intent){
        if(intent.getData()!=null&&"nogar".equals(intent.getData().getScheme())){
            try{acceptInvite(intent.getData().toString());intent.setData(null);}catch(Exception e){js("notice('Invito non valido')");}
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(ready)handleInvite(intent);}

    @JavascriptInterface public void copyCode(String code) {
        if (!code.matches("[0-9]{6}")) return;
        TrackingService active=TrackingService.current;
        String invitation=active==null?null:active.invitation();
        if(invitation==null){js("notice('Invito non disponibile: partita terminata')");return;}
        runOnUiThread(() -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Invito privato Nogar", invitation));
            js("notice('Invito copiato. Condividilo solo con i partecipanti: contiene la chiave privata della partita.')");
        });
    }

    @JavascriptInterface public void stopTracking() {
        runOnUiThread(() -> {
            pendingPhoneRequest=null;
            if (TrackingService.current != null)
                startService(new Intent(this, TrackingService.class).setAction("STOP"));
        });
    }

    @Override public void onRequestPermissionsResult(int r, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(r, permissions, results);
        if (r == 42) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (pendingPhoneRequest != null) startPhoneRequest();
                requestNotifications();
            } else {pendingPhoneRequest=null;js("setBusy(false);notice('La posizione precisa è necessaria: abilitala nelle impostazioni dell’app')");}
        } else if (r == 43 && results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED) {
            js("notice('Notifiche disattivate: abilita le notifiche per vedere il comando Ferma GPS')");
        }
    }

    @Override protected void onResume() {
        super.onResume(); visible = true;
        TrackingService.screen = new java.lang.ref.WeakReference<>(this);
        if (ready) {
            if (TrackingService.snapshot != null) restoreMatch();
            else if (TrackingService.current == null && pendingPhoneRequest == null) js("nativeResume()");
        }
        if (pendingPhoneRequest != null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startPhoneRequest();
    }
    @Override protected void onPause() { visible = false; super.onPause(); }
    @Override protected void onDestroy() {
        if (TrackingService.screen.get() == this) TrackingService.screen.clear();
        ready = false;
        net.shutdown();
        web.destroy();
        super.onDestroy();
    }
}

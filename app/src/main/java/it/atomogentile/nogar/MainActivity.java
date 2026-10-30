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
    private String endpoint = "", pendingSession;
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
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " NogarHunt/0.2.0");
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.addJavascriptInterface(this, "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) { ready = true; restoreMatch(); }
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

    @JavascriptInterface public String savedServer() { return getPreferences(0).getString("server", ""); }

    @JavascriptInterface public void connect(String url) {
        try {
            URI u = new URI(url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null) throw new Exception();
            endpoint = url.replaceAll("/+$", "");
            getPreferences(0).edit().putString("server", endpoint).apply();
            js("connected()");
        } catch (Exception e) { js("notice('Serve un indirizzo server HTTPS valido')"); }
    }

    @JavascriptInterface public void api(String body) {
        final String target = endpoint;
        net.execute(() -> {
            try { js("receive(" + ApiClient.post(target, new JSONObject(body)).toString() + ")"); }
            catch (Exception e) { js("networkError()"); }
        });
    }

    @JavascriptInterface public void startTracking(String session) {
        runOnUiThread(() -> {
            if (TrackingService.current != null) return;
            pendingSession = session;
            if (!visible) return; // Android requires starting a location service while visible.
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestGps(); return;
            }
            try {
                JSONObject auth = new JSONObject(session);
                Intent intent = new Intent(this, TrackingService.class)
                        .putExtra("endpoint", endpoint).putExtra("code", auth.getString("code"))
                        .putExtra("token", auth.getString("token"));
                startForegroundService(intent);
                pendingSession = null;
            } catch (Exception e) { js("notice('Apri l’app e autorizza GPS per avviare la condivisione')"); }
        });
    }

    @JavascriptInterface public void copyCode(String code) {
        if (!code.matches("[0-9]{6}")) return;
        runOnUiThread(() -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Codice lobby Nogar", code));
            js("notice('Codice lobby copiato')");
        });
    }

    @JavascriptInterface public void stopTracking() {
        runOnUiThread(() -> {
            pendingSession = null;
            if (TrackingService.current != null)
                startService(new Intent(this, TrackingService.class).setAction("STOP"));
        });
    }

    @Override public void onRequestPermissionsResult(int r, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(r, permissions, results);
        if (r == 42) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (pendingSession != null) startTracking(pendingSession);
                requestNotifications();
            } else js("notice('La posizione precisa è necessaria: abilitala nelle impostazioni dell’app')");
        } else if (r == 43 && results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED) {
            js("notice('Notifiche disattivate: abilita le notifiche per vedere il comando Ferma GPS')");
        }
    }

    @Override protected void onResume() {
        super.onResume(); visible = true;
        TrackingService.screen = new java.lang.ref.WeakReference<>(this);
        if (ready) {
            if (TrackingService.snapshot != null) restoreMatch();
            else if (TrackingService.current == null && pendingSession == null) js("nativeResume()");
        }
        if (pendingSession != null) startTracking(pendingSession);
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

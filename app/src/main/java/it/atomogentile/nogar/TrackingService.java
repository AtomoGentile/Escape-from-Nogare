package it.atomogentile.nogar;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Owns GPS and networking independently of the WebView and Activity lifecycle. */
public class TrackingService extends Service implements LocationListener {
    static volatile TrackingService current;
    static volatile String snapshot;
    static volatile WeakReference<MainActivity> screen = new WeakReference<>(null);
    private static final String CHANNEL = "live-location";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Location fix;
    private volatile boolean stopped;
    private boolean completed;
    private String endpoint, code, token;
    private LocationManager gps;
    private PowerManager.WakeLock wakeLock;
    private ScheduledFuture<?> loop;

    static void deliver(String script) {
        MainActivity activity = screen.get();
        if (activity != null) activity.js(script);
    }

    @Override public void onCreate() {
        super.onCreate();
        gps = (LocationManager) getSystemService(LOCATION_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Condivisione posizione partita", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TrackingService.class).setAction("STOP");
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(it.atomogentile.nogar.R.drawable.ic_notification)
                .setContentTitle("NOGAR · GPS attivo · Lobby " + code)
                .setContentText("Posizione condivisa ai ping. Tocca per tornare alla partita.")
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "ESCI E FERMA GPS", stopIntent).build())
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("STOP".equals(intent.getAction())) {
            finishTracking(true); return START_NOT_STICKY;
        }
        if (loop != null) return START_NOT_STICKY;
        endpoint = intent.getStringExtra("endpoint");
        code = intent.getStringExtra("code");
        token = intent.getStringExtra("token");
        if (endpoint == null || code == null || token == null
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            deliver("trackingStopped('Autorizza la posizione precisa e rientra nella lobby')");
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(7, notification());
            current = this;
            snapshot = null;
            // Session limit is 120 min; bounded lock keeps native pings running with screen off.
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nogar:match");
            wakeLock.acquire(135 * 60 * 1000L);
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            if (gps.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                gps.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, this);
            loop = worker.scheduleWithFixedDelay(this::tick, 0, 2200, TimeUnit.MILLISECONDS);
            // Never keep GPS on indefinitely in an abandoned pre-game lobby.
            main.postDelayed(() -> finishTracking(true), 130 * 60 * 1000L);
        } catch (Exception e) {
            deliver("trackingStopped('Impossibile avviare GPS: apri l’app e controlla i permessi')");
            finishTracking(true);
        }
        return START_NOT_STICKY;
    }

    private JSONObject request(String action) throws JSONException {
        return new JSONObject().put("action", action).put("code", code).put("token", token);
    }

    private void tick() {
        if (stopped) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> finishTracking(true)); return;
        }
        try {
            Location l = fix;
            boolean fresh = l != null && SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 10_000_000_000L;
            JSONObject body = request(fresh ? "location" : "poll");
            if (fresh) body.put("location", new JSONObject().put("lat", l.getLatitude())
                    .put("lng", l.getLongitude()).put("accuracy", l.getAccuracy()));
            JSONObject result = ApiClient.post(endpoint, body);
            if (stopped) return;
            if (result.has("error")) {
                deliver("notice(" + JSONObject.quote(result.getString("error")) + ")");
                if ("AUTH_INVALID".equals(result.optString("errorCode")) || "ROOM_GONE".equals(result.optString("errorCode")))
                    main.post(() -> finishTracking(false));
                return;
            }
            snapshot = result.toString();
            deliver("receive(" + snapshot + ")");
            if (!result.isNull("started") && result.optDouble("remaining", 1) <= 0) {
                main.post(() -> { completed = true; finishTracking(false); });
            }
        } catch (Exception e) {
            if (!stopped) deliver("networkError()");
        }
    }

    private void finishTracking(boolean leave) {
        if (stopped) return;
        stopped = true;
        if (loop != null) loop.cancel(false);
        if (leave && code != null && token != null) {
            try { worker.execute(() -> { try { ApiClient.post(endpoint, request("leave")); } catch (Exception ignored) {} }); }
            catch (RejectedExecutionException ignored) {}
        }
        if (!completed) {
            snapshot = null;
            deliver("trackingStopped('Condivisione posizione terminata')");
        } else deliver("trackingEnded()");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    void emitFix() {
        Location l = fix;
        if (l != null && !stopped && SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 10_000_000_000L)
            deliver("onGps(" + l.getLatitude() + "," + l.getLongitude() + "," + l.getAccuracy() + ")");
    }

    @Override public void onLocationChanged(Location location) {
        if (location.hasAccuracy() && location.getAccuracy() <= 1000) {
            fix = location;
            deliver("onGps(" + location.getLatitude() + "," + location.getLongitude() + "," + location.getAccuracy() + ")");
        }
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) { deliver("notice('GPS disattivato: riattivalo per inviare nuovi ping')"); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        gps.removeUpdates(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        worker.shutdown();
        if (current == this) current = null;
        if (!completed) snapshot = null;
        super.onDestroy();
    }
}

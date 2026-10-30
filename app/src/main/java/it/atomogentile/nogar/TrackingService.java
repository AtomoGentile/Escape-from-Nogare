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
    private volatile boolean completed;
    private boolean booting;
    private String endpoint, code = "…";
    private volatile PhoneSession session;
    private long nextPingAt;
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
                .setContentTitle(completed ? "NOGAR · Partita terminata · GPS fermo" : "NOGAR · GPS attivo · Lobby " + code)
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
        if (booting || loop != null) return START_NOT_STICKY;
        booting=true;
        endpoint = intent.getStringExtra("endpoint");
        String phoneRequest = intent.getStringExtra("phoneRequest");
        if (endpoint == null || phoneRequest == null
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
            // Satellite GPS only; no application-level network geolocation provider.
            worker.execute(() -> {
                try {
                    JSONObject creation = new JSONObject(phoneRequest);
                    session = PhoneSession.open(this, endpoint, creation);
                    if(stopped){session.finish();return;}
                    code = session.code();
                    JSONObject first = session.request(creation.getString("action").equals("create")
                            ? new JSONObject().put("action","poll") : creation);
                    if(first.has("error"))throw new IllegalArgumentException(first.getString("error"));
                    if(stopped){session.finish();return;}
                    publish(first);
                    main.post(() -> { if(!stopped)getSystemService(NotificationManager.class).notify(7,notification()); });
                    loop = worker.scheduleWithFixedDelay(this::tick, 0, 2200, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    main.post(() -> { if(stopped)return; finishTracking(true); deliver("connectionFailed('Impossibile aprire la partita. Controlla il servizio e l’invito; se era in pausa, attendi e riprova.')"); });
                }
            });
            // Never keep GPS on indefinitely in an abandoned pre-game lobby.
            main.postDelayed(() -> finishTracking(true), 130 * 60 * 1000L);
        } catch (Exception e) {
            deliver("trackingStopped('Impossibile avviare GPS: apri l’app e controlla i permessi')");
            finishTracking(true);
        }
        return START_NOT_STICKY;
    }

    private JSONObject request(String action) throws JSONException {return new JSONObject().put("action",action);}
    JSONObject command(JSONObject body) throws Exception {
        PhoneSession active=session;if(active==null)throw new IllegalStateException("Connessione in corso");return active.request(body);
    }
    String invitation(){PhoneSession active=session;return active==null?null:active.invitation();}
    private void publish(JSONObject result){
        if(stopped)return;
        snapshot=result.toString();nextPingAt=SystemClock.elapsedRealtime()+(long)(result.optDouble("nextPingIn",0)*1000);
        deliver("receive("+snapshot+")");
    }

    private void tick() {
        if (stopped || completed) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> finishTracking(true)); return;
        }
        try {
            Location l = fix;
            boolean fresh = l != null && SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 10_000_000_000L;
            boolean pingDue = nextPingAt<=SystemClock.elapsedRealtime();
            if(snapshot!=null){JSONObject previous=new JSONObject(snapshot);if(previous.isNull("started"))pingDue=true;}
            JSONObject body = request(fresh && pingDue ? "location" : "poll");
            if (fresh && pingDue) body.put("location", new JSONObject().put("lat", l.getLatitude())
                    .put("lng", l.getLongitude()).put("accuracy", l.getAccuracy()));
            JSONObject result = command(body);
            if (stopped) return;
            if (result.has("error")) {
                deliver("notice(" + JSONObject.quote(result.getString("error")) + ")");
                if ("AUTH_INVALID".equals(result.optString("errorCode")) || "ROOM_GONE".equals(result.optString("errorCode")))
                    main.post(() -> finishTracking(false));
                return;
            }
            publish(result);
            if (!result.isNull("started") && result.optDouble("remaining", 1) <= 0) {
                main.post(() -> {
                    if(completed||stopped)return;
                    completed=true;gps.removeUpdates(this);fix=null;
                    if(result.optBoolean("host")){
                        getSystemService(NotificationManager.class).notify(7,notification());
                        // Leave a short grace period for remote peers to fetch their final result.
                        main.postDelayed(()->finishTracking(false),12000);
                    } else finishTracking(false);
                });
            }
        } catch (Exception e) {
            if (!stopped) deliver("networkError()");
        }
    }

    private void finishTracking(boolean leave) {
        if (stopped) return;
        stopped = true;
        if (loop != null) loop.cancel(false);
        try { worker.execute(() -> { PhoneSession active=session;if(active!=null)active.finish(); }); }
        catch (RejectedExecutionException ignored) {}
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
        if (!stopped && !completed && location.hasAccuracy() && location.getAccuracy() <= 1000) {
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
        try { worker.execute(() -> { PhoneSession active=session;if(active!=null)active.finish(); }); } catch(RejectedExecutionException ignored){}
        worker.shutdown();
        if (current == this) current = null;
        if (!completed) snapshot = null;
        super.onDestroy();
    }
}

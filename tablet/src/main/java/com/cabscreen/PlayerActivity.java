package com.cabscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen ad player for the cab tablet.
 * Every second it asks the server "is a ride on, and which videos?". New videos are downloaded
 * to the tablet first, so playback never stutters on a weak connection. During a ride the videos
 * loop in the order set on the website; when the ride ends, playback stops.
 * Long-press anywhere to change the server address.
 */
public class PlayerActivity extends Activity {

    // Cloud API (Firebase). Must match FLEET_KEY in functions/.env.
    private static final String DEFAULT_SERVER = "https://cabscreen-ads.web.app/api";
    private static final String FLEET_KEY = BuildConfig.FLEET_KEY;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService downloader = Executors.newSingleThreadExecutor();
    private final Set<String> downloading = new HashSet<>();

    private VideoView video;
    private TextView status;
    private SharedPreferences prefs;
    private File dir;
    private volatile boolean running;

    // Main-thread state
    private boolean rideActive;
    private List<String> playlist = new ArrayList<>(); // names that are fully downloaded, in server order
    private volatile String current = "";                      // name playing now, "" when stopped
    private int index;

    // Latest GPS/network fix, sent to the server with every poll so the website can show where the cab is.
    private volatile Location fix;
    // Anonymous class, not a lambda: on Android 10 and older these methods have no default body.
    private final LocationListener onFix = new LocationListener() {
        @Override public void onLocationChanged(Location l) { if (better(l, fix)) fix = l; }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.i("CabScreen", "player started");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("cabscreen", MODE_PRIVATE);
        dir = new File(getFilesDir(), "videos");
        dir.mkdirs();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(14);
        status.setPadding(24, 16, 24, 16);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        setContentView(root);

        video.setOnCompletionListener(mp -> next());
        video.setOnErrorListener((mp, what, extra) -> { next(); return true; }); // bad file: skip it
        root.setOnLongClickListener(v -> { askServer(); return true; });
        video.setOnLongClickListener(v -> { askServer(); return true; });

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        startLocation();
    }

    private void startLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.removeUpdates(onFix);
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) continue;
                onFix.onLocationChanged(lm.getLastKnownLocation(p)); // ignored if null
                lm.requestLocationUpdates(p, 5000, 0, onFix, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) { } // no permission yet / no such provider
    }

    /** Prefer a newer fix; within 30s of each other, prefer the more accurate one. */
    static boolean better(Location n, Location old) {
        if (n == null) return false;
        if (old == null) return true;
        long dt = n.getTime() - old.getTime();
        if (dt > 30000) return true;
        if (dt < -30000) return false;
        return n.getAccuracy() <= old.getAccuracy() || dt > 0 && !old.hasAccuracy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        running = true;
        new Thread(this::pollLoop, "poll").start();
        startLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(onFix);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private String base() {
        String s = prefs.getString("server", DEFAULT_SERVER).trim();
        return s.startsWith("http") ? s : "http://" + s;
    }

    // ---------- background: poll the server, sync files ----------

    private void pollLoop() {
        while (running) {
            try {
                String url = base() + "/state?key=" + FLEET_KEY + "&id=" + enc(deviceId())
                        + "&tablet=" + enc(Build.MANUFACTURER + " " + Build.MODEL) + "&playing=" + enc(current);
                Location l = fix;
                if (l != null) url += "&lat=" + l.getLatitude() + "&lng=" + l.getLongitude()
                        + "&acc=" + Math.round(l.getAccuracy()) + "&fix=" + l.getTime();
                JSONObject s = new JSONObject(get(url));
                if (s.optBoolean("restart")) { // "Restart app" pressed on the website
                    android.util.Log.i("CabScreen", "restart requested by website");
                    running = false;
                    // ponytail: recreate() rebuilds the player screen in the same task, so screen pinning
                    // stays on. It does not kill the process; add a full process restart if that's ever needed.
                    ui.post(this::recreate);
                    return;
                }
                boolean active = s.getJSONObject("ride").getBoolean("active");
                JSONArray vids = s.getJSONArray("videos");

                List<String> ready = new ArrayList<>();
                Set<String> wanted = new HashSet<>();
                for (int i = 0; i < vids.length(); i++) {
                    JSONObject v = vids.getJSONObject(i);
                    String name = new File(v.getString("name")).getName();
                    long size = v.getLong("size");
                    wanted.add(name);
                    File f = new File(dir, name);
                    if (f.length() == size) ready.add(name);
                    else download(name, size, v.optString("url", base() + "/videos/" + enc(name)));
                }
                File[] local = dir.listFiles();
                if (local != null) for (File f : local) {
                    String n = f.getName().replace(".part", "");
                    if (!wanted.contains(n) && !current.equals(n)) f.delete(); // removed on the website
                }
                int pending = vids.length() - ready.size();
                ui.post(() -> apply(active, ready, pending, null));
            } catch (Exception e) {
                // Server unreachable: keep doing whatever we were doing (a ride in progress keeps playing).
                ui.post(() -> apply(rideActive, playlist, 0, "Server not reachable: " + base()));
            }
            sleep(2000);
        }
    }

    private String deviceId() {
        return android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
    }

    private void download(String name, long size, String src) {
        synchronized (downloading) {
            if (!downloading.add(name)) return;
        }
        downloader.execute(() -> {
            File part = new File(dir, name + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                if (part.length() == size) part.renameTo(new File(dir, name));
                else part.delete();
            } catch (Exception e) {
                part.delete(); // retried on a later poll
            } finally {
                synchronized (downloading) { downloading.remove(name); }
            }
        });
    }

    // ---------- main thread: play or stop ----------

    private void apply(boolean active, List<String> ready, int pending, String error) {
        rideActive = active;
        playlist = ready;
        if (active && !ready.isEmpty()) {
            if (current.isEmpty() || !ready.contains(current)) play(index);
        } else if (!current.isEmpty()) {
            stop();
        }

        String msg;
        if (error != null) msg = error;
        else if (!active) msg = "Waiting for ride…";
        else if (ready.isEmpty()) msg = pending > 0 ? "Downloading videos…" : "No videos uploaded";
        else msg = "";
        if (error == null && pending > 0 && !ready.isEmpty()) msg = "Downloading " + pending + " more video(s)…";
        status.setText(msg);
    }

    private void play(int i) {
        if (playlist.isEmpty()) { stop(); return; }
        index = ((i % playlist.size()) + playlist.size()) % playlist.size();
        current = playlist.get(index);
        video.setVideoPath(new File(dir, current).getAbsolutePath());
        video.start();
    }

    private void next() {
        if (rideActive) play(index + 1); else stop();
    }

    private void stop() {
        video.stopPlayback();
        video.setVideoURI(null);
        current = "";
    }

    private void askServer() {
        EditText input = new EditText(this);
        input.setText(prefs.getString("server", DEFAULT_SERVER));
        new AlertDialog.Builder(this)
                .setTitle("Server address")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> prefs.edit().putString("server", input.getText().toString().trim()).apply())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- small helpers ----------

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}

package com.ridescan;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileWriter;
import java.io.IOException;

/** Android calls this for every notification once the user grants notification access. */
public class RideListener extends NotificationListenerService {

    static final String LOG = "rides.tsv";
    static final String PREFS = "ridescan", SERVER = "server_url"; // new key: old saved Wi-Fi address is ignored
    // Your laptop's Wi-Fi address today; change it in the app if the laptop gets a new one.
    // Cloud API (Firebase). FLEET_KEY must match functions/.env.
    static final String DEFAULT_SERVER = "https://cabscreen-ads.web.app/api";
    static final String FLEET_KEY = BuildConfig.FLEET_KEY;

    static String server(android.content.Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(SERVER, DEFAULT_SERVER).trim();
    }

    static boolean enabled(SharedPreferences p, String app) { return p.getBoolean("app_" + app, true); }

    /** Set by MainActivity while it is on screen; called on the main thread right after a save. */
    static volatile Runnable onNew;

    // e.g. "Google Pixel 9"; shown on the laptop page so you know which phone sent what.
    private static final String DEVICE = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private String lastLine = "";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String app = RideParser.appName(sbn.getPackageName());
        if (app == null || !enabled(getSharedPreferences(PREFS, MODE_PRIVATE), app)) return;

        Bundle x = sbn.getNotification().extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        String all = title + " " + text;
        if (all.trim().isEmpty()) return;

        String type = RideParser.classify(app, title, all);
        String line = app + "\t" + type + "\t" + RideParser.fare(all) + "\t" + title + "\t" + text;
        if (line.equals(lastLine)) return; // ongoing notifications re-post the same text often
        lastLine = line;
        long now = System.currentTimeMillis();
        append(new File(getFilesDir(), LOG), now + "\t" + line + "\n");
        Runnable ui = onNew;
        if (ui != null) ui.run(); // listener callbacks already run on the main thread

        String server = server(this);
        if (server.isEmpty()) return;
        try {
            JSONObject j = new JSONObject().put("time", now).put("app", app).put("type", type)
                    .put("fare", RideParser.fare(all)).put("title", title).put("text", text)
                    .put("device", DEVICE).put("android", android.os.Build.VERSION.RELEASE);
            NET.execute(() -> post(server, j.toString()));
        } catch (Exception ignored) { }
    }

    // ponytail: fire-and-forget. If the laptop is off, that event only stays in the phone's history.
    // Add a retry queue if missed events matter.
    private static void post(String server, String json) {
        try {
            String base = server.startsWith("http") ? server : "http://" + server;
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/event?key=" + FLEET_KEY).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) { }
    }

    static synchronized void append(File f, String s) {
        try (FileWriter w = new FileWriter(f, true)) { w.write(s); } catch (IOException ignored) { }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().replace('\t', ' ').replace('\n', ' ').trim();
    }
}

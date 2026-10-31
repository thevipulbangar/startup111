package com.ridescan;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class MainActivity extends Activity {

    private static final int MAX_SHOWN = 300; // keeps redraws instant even with a huge history

    private TextView status, list;
    private CheckBox showOther;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(RideListener.PREFS, MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        root.addView(status);

        Button grant = new Button(this);
        grant.setText("Grant notification access");
        grant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(grant);

        // App switches: unticked apps are neither saved, sent to the laptop, nor shown.
        TextView appsLabel = new TextView(this);
        appsLabel.setText("Scan these apps:");
        root.addView(appsLabel);
        LinearLayout apps = new LinearLayout(this);
        for (String app : RideParser.APPS) {
            CheckBox c = new CheckBox(this);
            c.setText(app);
            c.setChecked(RideListener.enabled(prefs, app));
            c.setOnCheckedChangeListener((v, on) -> {
                prefs.edit().putBoolean("app_" + app, on).apply();
                refresh();
            });
            apps.addView(c);
        }
        root.addView(apps);

        showOther = new CheckBox(this);
        showOther.setText("Also show other notifications (non start/end)");
        showOther.setChecked(true);
        showOther.setOnCheckedChangeListener((v, c) -> refresh());
        root.addView(showOther);

        EditText server = new EditText(this);
        server.setHint("Server, e.g. https://your-project.web.app/api");
        server.setSingleLine(true);
        server.setText(RideListener.server(this));
        root.addView(server);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button("Save server", () -> {
            prefs.edit().putString(RideListener.SERVER, server.getText().toString().trim()).apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }));
        buttons.addView(button("Refresh", this::refresh));
        buttons.addView(button("Clear", () -> { log().delete(); refresh(); }));
        root.addView(buttons);

        list = new TextView(this);
        list.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll);
        setContentView(root);
    }

    private Button button(String text, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setOnClickListener(v -> onClick.run());
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return btn;
    }

    @Override
    protected void onResume() {
        super.onResume();
        RideListener.onNew = this::refresh; // listener calls this the moment a notification is saved
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        RideListener.onNew = null;
    }

    private File log() { return new File(getFilesDir(), RideListener.LOG); }

    private void refresh() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean on = enabled != null && enabled.contains(new ComponentName(this, RideListener.class).flattenToString());
        status.setText(on ? "✅ Listening for notifications" : "❌ Notification access is OFF — tap the button below");

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        try {
            List<String> lines = Files.readAllLines(log().toPath(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && shown < MAX_SHOWN; i--) {
                String[] f = lines.get(i).split("\t", -1); // time, app, type, fare, title, text
                if (f.length < 6) continue;
                if (!RideListener.enabled(prefs, f[1])) continue;
                if (f[2].equals("OTHER") && !showOther.isChecked()) continue;
                String label = f[2].equals("START") ? "🟢 RIDE STARTED" : f[2].equals("END") ? "🔴 RIDE ENDED" : "⚪ other";
                sb.append(DateFormat.format("dd MMM, hh:mm a", Long.parseLong(f[0])))
                  .append("  ").append(f[1]).append("  ").append(label);
                if (!f[3].isEmpty()) sb.append("  ₹").append(f[3]);
                sb.append("\n").append(f[4]).append(" — ").append(f[5]).append("\n\n");
                shown++;
            }
        } catch (Exception ignored) { }
        list.setText(shown == 0 ? "No notifications yet." : sb);
    }
}

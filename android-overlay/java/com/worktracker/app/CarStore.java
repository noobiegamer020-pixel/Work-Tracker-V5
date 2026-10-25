package com.worktracker.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Shared storage and logic used by the receiver, the trip service and the plugin. */
public class CarStore {
    static final String PREFS = "wt_car";
    static final String CH_MORNING = "car_morning";
    static final String CH_TRIP = "car_trip";
    static final String CH_ALERT = "car_alert";

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_MORNING, "Morning car notification", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_TRIP, "Trip recording (silent)", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Oil change alerts", NotificationManager.IMPORTANCE_HIGH));
    }

    /** True when the device is the car chosen in the app (by saved address or by name). */
    static boolean isTarget(Context c, BluetoothDevice d) {
        try {
            SharedPreferences p = sp(c);
            String addr = p.getString("addr", null);
            if (addr != null && addr.equalsIgnoreCase(d.getAddress())) return true;
            String n = d.getName();
            String t = p.getString("name", "B9");
            return n != null && t != null && n.trim().equalsIgnoreCase(t.trim());
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Car connected: open a session (once) and send the single morning notification of the day. */
    static synchronized void onConnect(Context c) {
        SharedPreferences p = sp(c);
        long now = System.currentTimeMillis();
        if (p.getBoolean("act", false)) {
            long last = p.getLong("act_last", 0);
            if (last > 0 && now - last > 15 * 60 * 1000L) end(c, last, null);
        }
        if (!p.getBoolean("act", false)) {
            p.edit().putBoolean("act", true).putLong("act_t0", now).putLong("act_m", 0)
                    .putLong("act_last", now).putBoolean("act_gps", false).apply();
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(p.getString("lastDay", ""))) {
            p.edit().putString("lastDay", today).apply();
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hour < 12) morning(c, today);
        }
    }

    static synchronized void addDist(Context c, long meters) {
        SharedPreferences p = sp(c);
        p.edit().putLong("act_m", p.getLong("act_m", 0) + meters)
                .putLong("act_last", System.currentTimeMillis()).putBoolean("act_gps", true).apply();
    }

    static synchronized void touch(Context c) {
        sp(c).edit().putLong("act_last", System.currentTimeMillis()).apply();
    }

    /** Close the open session, store it for the app, and check the oil thresholds. */
    static synchronized void end(Context c, long tEnd, JSONArray pts) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("act", false)) return;
        long t0 = p.getLong("act_t0", tEnd);
        long m = p.getLong("act_m", 0);
        boolean gps = p.getBoolean("act_gps", false);
        SharedPreferences.Editor e = p.edit().putBoolean("act", false);
        if (tEnd - t0 < 120000L && m < 300) { // short blip of the Bluetooth link: ignore
            e.apply();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("t0", t0);
            o.put("t1", tEnd);
            o.put("m", m);
            o.put("gps", gps);
            if (pts != null) o.put("pts", pts);
            JSONArray arr = new JSONArray(p.getString("sessions", "[]"));
            arr.put(o);
            while (arr.length() > 60) arr.remove(0);
            e.putString("sessions", arr.toString());
        } catch (Exception ex) {
            // keep going: the oil counter below must still be updated
        }
        long os = p.getLong("oilStart", 0);
        if (os > 0 && t0 >= os) {
            long tot = p.getLong("oilM", 0) + m;
            e.putLong("oilM", tot);
            long target = p.getLong("oilTarget", 10000000L);
            if (!p.getBoolean("f95", false) && tot >= target - 500000L) {
                e.putBoolean("f95", true);
                alert(c, "Oil change soon", "About " + (tot / 1000) + " km since the last oil change.", 7101);
            }
            if (!p.getBoolean("f100", false) && tot >= target) {
                e.putBoolean("f100", true);
                alert(c, "Oil change due", (target / 1000) + " km reached. Change the oil, then tap Oil changed in the app.", 7102);
            }
        }
        e.apply();
    }

    static PendingIntent openApp(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, 0, i, flags);
    }

    static void morning(Context c, String today) {
        String title = "Good morning";
        String body = "Car connected. Tap to open today's plan.";
        try {
            JSONObject all = new JSONObject(sp(c).getString("morning", "{}"));
            JSONObject o = all.optJSONObject(today);
            if (o != null) {
                title = o.optString("t", title);
                body = o.optString("b", body);
            }
        } catch (Exception ex) {
            // use the defaults
        }
        notify(c, CH_MORNING, 7100, title, body, NotificationCompat.PRIORITY_DEFAULT);
    }

    static void alert(Context c, String title, String body, int id) {
        notify(c, CH_ALERT, id, title, body, NotificationCompat.PRIORITY_HIGH);
    }

    static void notify(Context c, String channel, int id, String title, String body, int prio) {
        try {
            ensureChannels(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
                    .setSmallIcon(R.drawable.ic_stat_icon)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(prio)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c));
            nm.notify(id, b.build());
        } catch (Exception ex) {
            // notifications may be blocked by the user
        }
    }
}

package com.worktracker.app;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

/**
 * Runs only while the phone is connected to the car. It counts the distance with GPS
 * during that time and stops 90 seconds after the car disconnects.
 */
public class CarTripService extends Service implements LocationListener {
    static final String ACT_START = "com.worktracker.app.CAR_START";
    static final String ACT_STOP = "com.worktracker.app.CAR_STOP";
    static volatile boolean RUNNING = false;
    private static final int NOTIF_ID = 7001;
    private static final long GRACE_MS = 90000L;

    private LocationManager lm;
    private Location lastCount;
    private Location lastPoint;
    private long lastPointT = 0;
    private final JSONArray pts = new JSONArray();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopper = new Runnable() {
        @Override
        public void run() {
            finishTrip();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACT_STOP.equals(a)) {
            handler.removeCallbacks(stopper);
            handler.postDelayed(stopper, GRACE_MS);
            return START_NOT_STICKY;
        }
        // start (or the car reconnected during the grace time)
        handler.removeCallbacks(stopper);
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!RUNNING) {
            RUNNING = true;
            if (!startLocation()) {
                RUNNING = false;
                stopForeground(true);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private boolean goForeground() {
        try {
            CarStore.ensureChannels(this);
            Notification n = new NotificationCompat.Builder(this, CarStore.CH_TRIP)
                    .setSmallIcon(R.drawable.ic_stat_icon)
                    .setContentTitle("Work Tracker")
                    .setContentText("Recording the trip while the car is connected")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setContentIntent(CarStore.openApp(this))
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean startLocation() {
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return false;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 10f, this, Looper.getMainLooper());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        if (l.hasAccuracy() && l.getAccuracy() > 60f) return;
        long now = System.currentTimeMillis();
        CarStore.touch(this);
        if (lastCount == null) {
            lastCount = l;
        } else {
            float d = lastCount.distanceTo(l);
            long dt = Math.max(1L, (l.getTime() - lastCount.getTime()) / 1000L);
            if (d / dt > 70f) { // impossible jump (over 250 km/h): ignore the fix
                lastCount = l;
            } else if (d >= 8f) {
                CarStore.addDist(this, (long) d);
                lastCount = l;
            }
        }
        if (lastPoint == null || lastPoint.distanceTo(l) >= 40f || now - lastPointT > 90000L) {
            try {
                JSONArray p = new JSONArray();
                p.put(l.getTime() / 1000L);
                p.put(Math.round(l.getLatitude() * 100000d) / 100000d);
                p.put(Math.round(l.getLongitude() * 100000d) / 100000d);
                if (pts.length() < 2500) pts.put(p);
            } catch (Exception e) {
                // skip this point
            }
            lastPoint = l;
            lastPointT = now;
        }
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    public void onProviderEnabled(String provider) {
    }

    public void onProviderDisabled(String provider) {
    }

    private void finishTrip() {
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception e) {
            // already removed
        }
        CarStore.end(this, System.currentTimeMillis(), pts);
        RUNNING = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(stopper);
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception e) {
            // ignore
        }
        RUNNING = false;
        super.onDestroy();
    }
}

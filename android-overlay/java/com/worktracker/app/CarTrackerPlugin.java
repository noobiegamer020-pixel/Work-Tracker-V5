package com.worktracker.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** JavaScript side of the car tracking: permissions, settings, and the trips recorded in the background. */
@CapacitorPlugin(name = "CarTracker")
public class CarTrackerPlugin extends Plugin {

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void permissionState(PluginCall call) {
        JSObject o = new JSObject();
        o.put("bluetooth", Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT));
        o.put("location", has(Manifest.permission.ACCESS_FINE_LOCATION));
        o.put("background", Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        o.put("notifications", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS));
        call.resolve(o);
    }

    @PluginMethod
    public void askPermissions(PluginCall call) {
        int step = call.getInt("step", 1);
        if (step == 1) {
            List<String> l = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
                l.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                l.add(Manifest.permission.ACCESS_FINE_LOCATION);
                l.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                l.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!l.isEmpty()) {
                ActivityCompat.requestPermissions(getActivity(), l.toArray(new String[0]), 7711);
            }
        } else if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            // Android 11+ sends the person to the settings page: choose "Allow all the time"
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 7712);
        }
        call.resolve();
    }

    @PluginMethod
    public void setConfig(PluginCall call) {
        SharedPreferences.Editor e = CarStore.sp(getContext()).edit();
        SharedPreferences p = CarStore.sp(getContext());
        String name = call.getString("name");
        if (name != null && !name.equals(p.getString("name", ""))) {
            e.putString("name", name);
            e.remove("addr");
            String ad = findAddress(name);
            if (ad != null) e.putString("addr", ad);
        }
        String morning = call.getString("morning");
        if (morning != null) e.putString("morning", morning);
        Long os = call.getLong("oilStartMs");
        if (os != null) e.putLong("oilStart", os);
        Long tg = call.getLong("oilTargetM");
        if (tg != null && tg > 0) e.putLong("oilTarget", tg);
        if (Boolean.TRUE.equals(call.getBoolean("resetOil", false))) {
            Long m = call.getLong("oilM");
            long tot = m == null ? 0L : m;
            long target = (tg != null && tg > 0) ? tg : p.getLong("oilTarget", 10000000L);
            e.putLong("oilM", tot);
            e.putBoolean("f95", tot >= target - 500000L);
            e.putBoolean("f100", tot >= target);
        }
        e.apply();
        call.resolve();
    }

    private String findAddress(String name) {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return null;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName();
                if (n != null && n.trim().equalsIgnoreCase(name.trim())) return d.getAddress();
            }
        } catch (Exception ex) {
            // permission not granted yet: the name is used instead
        }
        return null;
    }

    @PluginMethod
    public void pairedDevices(PluginCall call) {
        JSArray arr = new JSArray();
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null) {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    JSObject o = new JSObject();
                    o.put("name", d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                }
            }
        } catch (SecurityException ex) {
            call.reject("Bluetooth permission is missing");
            return;
        }
        JSObject r = new JSObject();
        r.put("devices", arr);
        call.resolve(r);
    }

    /** Returns the finished trips and clears them. */
    @PluginMethod
    public void drain(PluginCall call) {
        SharedPreferences p = CarStore.sp(getContext());
        String s;
        synchronized (CarStore.class) {
            s = p.getString("sessions", "[]");
            p.edit().putString("sessions", "[]").apply();
        }
        JSObject r = new JSObject();
        try {
            r.put("sessions", new JSArray(s));
        } catch (JSONException ex) {
            r.put("sessions", new JSArray());
        }
        call.resolve(r);
    }

    @PluginMethod
    public void status(PluginCall call) {
        SharedPreferences p = CarStore.sp(getContext());
        JSObject r = new JSObject();
        r.put("connected", p.getBoolean("act", false));
        r.put("since", p.getLong("act_t0", 0));
        call.resolve(r);
    }
}

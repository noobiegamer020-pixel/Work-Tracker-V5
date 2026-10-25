package com.worktracker.app;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Gets the Bluetooth connect / disconnect events, even when the app is closed. */
public class CarReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        BluetoothDevice d;
        if (Build.VERSION.SDK_INT >= 33) {
            d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }
        if (d == null || !CarStore.isTarget(c, d)) return;

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
            CarStore.onConnect(c);
            try {
                Intent s = new Intent(c, CarTripService.class).setAction(CarTripService.ACT_START);
                ContextCompat.startForegroundService(c, s);
            } catch (Exception e) {
                // the trip is still recorded (time only); distance needs the service
            }
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
            boolean handled = false;
            if (CarTripService.RUNNING) {
                try {
                    c.startService(new Intent(c, CarTripService.class).setAction(CarTripService.ACT_STOP));
                    handled = true;
                } catch (Exception e) {
                    handled = false;
                }
            }
            if (!handled) CarStore.end(c, System.currentTimeMillis(), null);
        }
    }
}

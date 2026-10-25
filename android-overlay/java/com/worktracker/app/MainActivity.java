package com.worktracker.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(CarTrackerPlugin.class);
        registerPlugin(ClockAlarmPlugin.class);
        super.onCreate(savedInstanceState);
        CarStore.ensureChannels(this);
    }
}

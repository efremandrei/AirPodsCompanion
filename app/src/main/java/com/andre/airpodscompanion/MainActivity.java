package com.andre.airpodscompanion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 41;
    private static final String PREFS = "airpods_companion_prefs";
    private static final String PREF_DARK = "dark_theme";
    private static final String PREF_AUTO_PAUSE = "auto_pause_enabled";
    private static final String PREF_CASE_POPUP = "case_popup_enabled";
    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final String REPO_URL = "https://github.com/efremandrei/AirPodsCompanion";
    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final long AIRPODS_SCAN_MS = 12000L;

    private final Map<String, Integer> batteryByAddress = new HashMap<>();
    private final Map<String, Set<Integer>> connectedProfiles = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences preferences;
    private boolean darkMode;
    private boolean autoPauseEnabled;
    private boolean casePopupEnabled;
    private Boolean lastWornState;
    private String lastLidState;
    private boolean autoPausedByApp;
    private long lastCasePopupAt;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback airPodsScanCallback;
    private boolean airPodsScanActive;
    private BluetoothProfile a2dpProfile;
    private BluetoothProfile headsetProfile;

    private LinearLayout root;
    private LinearLayout deviceList;
    private TextView headlineStatus;
    private TextView detailStatus;
    private TextView profileStatus;
    private TextView audioStatus;
    private TextView widgetBatteryStatus;
    private TextView experimentalStatus;
    private TextView hardwareStatus;
    private TextView lastUpdated;

    private int bg;
    private int panel;
    private int text;
    private int muted;
    private int accent;
    private int warning;
    private int buttonBg;

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy;
            } else if (profile == BluetoothProfile.HEADSET) {
                headsetProfile = proxy;
            }
            refreshDevices();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = null;
            } else if (profile == BluetoothProfile.HEADSET) {
                headsetProfile = null;
            }
            refreshDevices();
        }
    };

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_BATTERY.equals(action)) {
                handleBatteryBroadcast(intent);
            }
            refreshDevices();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUpdateChecker.checkDaily(this);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        darkMode = preferences.getBoolean(PREF_DARK, true);
        autoPauseEnabled = preferences.getBoolean(PREF_AUTO_PAUSE, false);
        casePopupEnabled = preferences.getBoolean(PREF_CASE_POPUP, true);
        bluetoothAdapter = getBluetoothAdapter();
        buildUi();
        requestBluetoothConnectIfNeeded();
        bindProfileProxies();
        refreshDevices();
        startAirPodsBatteryScanIfPossible();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_BATTERY);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
        closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
        stopAirPodsBatteryScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            bindProfileProxies();
            refreshDevices();
            startAirPodsBatteryScanIfPossible();
        }
    }

    private void buildUi() {
        applyPalette();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(bg);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(topBar, matchWrap());

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        topBar.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = label("AirPods Companion", 28, Typeface.BOLD, text);
        titleBox.addView(title);
        TextView subtitle = label("Bluetooth status, battery, and pairing tools for Apple earbuds on Android.", 14, Typeface.NORMAL, muted);
        titleBox.addView(subtitle);

        Button darkButton = smallButton("☾");
        darkButton.setContentDescription("Use dark skin");
        darkButton.setOnClickListener(v -> setDarkMode(true));
        topBar.addView(darkButton, fixedButton());

        Button lightButton = smallButton("☀");
        lightButton.setContentDescription("Use light skin");
        lightButton.setOnClickListener(v -> setDarkMode(false));
        topBar.addView(lightButton, fixedButton());

        Button aboutButton = smallButton("i");
        aboutButton.setContentDescription("About");
        aboutButton.setOnClickListener(v -> showAbout());
        topBar.addView(aboutButton, fixedButton());

        LinearLayout summary = panel();
        summary.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(summary, blockMargins());

        headlineStatus = label("Checking Bluetooth", 22, Typeface.BOLD, text);
        summary.addView(headlineStatus);
        detailStatus = label("", 15, Typeface.NORMAL, muted);
        detailStatus.setPadding(0, dp(8), 0, 0);
        summary.addView(detailStatus);
        profileStatus = label("", 14, Typeface.NORMAL, muted);
        profileStatus.setPadding(0, dp(10), 0, 0);
        summary.addView(profileStatus);
        audioStatus = label("", 14, Typeface.NORMAL, muted);
        audioStatus.setPadding(0, dp(6), 0, 0);
        summary.addView(audioStatus);
        widgetBatteryStatus = label("", 14, Typeface.NORMAL, muted);
        widgetBatteryStatus.setPadding(0, dp(6), 0, 0);
        summary.addView(widgetBatteryStatus);
        experimentalStatus = label("", 14, Typeface.NORMAL, muted);
        experimentalStatus.setPadding(0, dp(6), 0, 0);
        summary.addView(experimentalStatus);
        hardwareStatus = label("", 14, Typeface.NORMAL, muted);
        hardwareStatus.setPadding(0, dp(6), 0, 0);
        summary.addView(hardwareStatus);
        lastUpdated = label("", 12, Typeface.NORMAL, muted);
        lastUpdated.setPadding(0, dp(10), 0, 0);
        summary.addView(lastUpdated);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actions, tightMargins());

        Button refresh = actionButton("Refresh");
        refresh.setOnClickListener(v -> {
            refreshDevices();
            startAirPodsBatteryScanIfPossible();
        });
        actions.addView(refresh, weightedAction());

        Button bluetoothSettings = actionButton("Bluetooth");
        bluetoothSettings.setOnClickListener(v -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        actions.addView(bluetoothSettings, weightedAction());

        Button soundSettings = actionButton("Sound");
        soundSettings.setOnClickListener(v -> openSettings(Settings.ACTION_SOUND_SETTINGS));
        actions.addView(soundSettings, weightedAction());

        CheckBox autoPause = new CheckBox(this);
        autoPause.setText("Experimental auto-pause while app is open");
        autoPause.setTextColor(text);
        autoPause.setTextSize(14);
        autoPause.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        autoPause.setChecked(autoPauseEnabled);
        autoPause.setPadding(0, dp(8), 0, 0);
        autoPause.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoPauseEnabled = isChecked;
            preferences.edit().putBoolean(PREF_AUTO_PAUSE, autoPauseEnabled).apply();
            refreshDevices();
            startAirPodsBatteryScanIfPossible();
        });
        root.addView(autoPause, matchWrap());

        CheckBox casePopup = new CheckBox(this);
        casePopup.setText("Show case-open popup while app is open");
        casePopup.setTextColor(text);
        casePopup.setTextSize(14);
        casePopup.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        casePopup.setChecked(casePopupEnabled);
        casePopup.setPadding(0, dp(4), 0, 0);
        casePopup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            casePopupEnabled = isChecked;
            preferences.edit().putBoolean(PREF_CASE_POPUP, casePopupEnabled).apply();
        });
        root.addView(casePopup, matchWrap());

        TextView devicesTitle = label("Devices", 19, Typeface.BOLD, text);
        devicesTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(devicesTitle);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList, matchWrap());

        LinearLayout guide = panel();
        guide.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.addView(guide, blockMargins());
        guide.addView(label("AirPods checklist", 18, Typeface.BOLD, text));
        guide.addView(paragraph("1. Put AirPods in the case, open the lid, then hold the back button until the light flashes white."));
        guide.addView(paragraph("2. Tap Bluetooth here and pair them in Android settings."));
        guide.addView(paragraph("3. Return to this app to see paired/connected status and any battery level Android exposes."));
        guide.addView(paragraph("4. If media still plays through the phone, open Sound and choose Bluetooth output."));
        guide.addView(paragraph("5. Add the home-screen widget for left, right, and case battery. Split values appear only when Android exposes AirPods BLE battery advertisements."));
        guide.addView(paragraph("6. Experimental auto-pause uses AirPods BLE ear-state and sends media play/pause events while this app is open."));
        guide.addView(paragraph("7. Case-open popup and model/microphone details are reverse-engineered from Apple Continuity proximity pairing frames."));

        setContentView(scrollView);
    }

    private void setDarkMode(boolean enabled) {
        if (darkMode == enabled) {
            return;
        }
        darkMode = enabled;
        preferences.edit().putBoolean(PREF_DARK, darkMode).apply();
        buildUi();
        refreshDevices();
    }

    private void applyPalette() {
        if (darkMode) {
            bg = Color.rgb(15, 20, 24);
            panel = Color.rgb(29, 36, 42);
            text = Color.rgb(245, 247, 250);
            muted = Color.rgb(179, 190, 199);
            accent = Color.rgb(52, 199, 89);
            warning = Color.rgb(255, 204, 0);
            buttonBg = Color.rgb(42, 52, 60);
        } else {
            bg = Color.rgb(245, 247, 249);
            panel = Color.WHITE;
            text = Color.rgb(21, 26, 31);
            muted = Color.rgb(88, 99, 110);
            accent = Color.rgb(0, 132, 68);
            warning = Color.rgb(145, 100, 0);
            buttonBg = Color.rgb(232, 237, 241);
        }
    }

    private void refreshDevices() {
        if (root == null) {
            return;
        }

        bluetoothAdapter = getBluetoothAdapter();
        connectedProfiles.clear();
        collectConnectedProfiles(BluetoothProfile.A2DP, a2dpProfile);
        collectConnectedProfiles(BluetoothProfile.HEADSET, headsetProfile);

        List<BluetoothDevice> bonded = getBondedDevicesSafely();
        List<BluetoothDevice> airPods = new ArrayList<>();
        List<BluetoothDevice> others = new ArrayList<>();
        for (BluetoothDevice device : bonded) {
            if (isAppleEarbud(deviceName(device))) {
                airPods.add(device);
            } else {
                others.add(device);
            }
        }

        boolean permission = hasBluetoothConnectPermission();
        boolean bluetoothAvailable = bluetoothAdapter != null;
        boolean enabled = bluetoothAvailable && bluetoothAdapter.isEnabled();
        boolean anyAirPodsConnected = false;
        for (BluetoothDevice device : airPods) {
            if (isConnected(device)) {
                anyAirPodsConnected = true;
                break;
            }
        }

        if (!bluetoothAvailable) {
            headlineStatus.setText("Bluetooth is not available");
            detailStatus.setText("This phone or build does not expose a Bluetooth adapter.");
        } else if (!permission) {
            headlineStatus.setText("Nearby devices permission needed");
            detailStatus.setText("Grant permission so the app can read paired AirPods and connection status.");
        } else if (!enabled) {
            headlineStatus.setText("Bluetooth is off");
            detailStatus.setText("Turn on Bluetooth, pair your AirPods, then refresh.");
        } else if (anyAirPodsConnected) {
            headlineStatus.setText("AirPods connected");
            detailStatus.setText("Android reports an Apple earbud profile as connected.");
        } else if (!airPods.isEmpty()) {
            headlineStatus.setText("AirPods paired");
            detailStatus.setText("They are saved on this phone but no media/call profile is currently connected.");
        } else {
            headlineStatus.setText("No AirPods found yet");
            detailStatus.setText("Pair AirPods from Bluetooth settings, then return here.");
        }

        profileStatus.setText(profileSummary());
        audioStatus.setText(audioSummary());
        updateWidgetBatterySummary();
        lastUpdated.setText("Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));

        deviceList.removeAllViews();
        if (!permission) {
            Button permissionButton = actionButton("Grant Nearby devices");
            permissionButton.setOnClickListener(v -> requestBluetoothConnectIfNeeded());
            deviceList.addView(permissionButton, matchWrap());
            deviceList.addView(paragraph("Android 12 and newer require Nearby devices permission for connected Bluetooth device details and AirPods battery scanning."));
            return;
        }

        if (airPods.isEmpty() && others.isEmpty()) {
            deviceList.addView(emptyPanel("No paired Bluetooth devices are visible to this app."));
            return;
        }

        for (BluetoothDevice device : airPods) {
            deviceList.addView(deviceRow(device, true), blockMargins());
        }
        for (BluetoothDevice device : others) {
            deviceList.addView(deviceRow(device, false), blockMargins());
        }
    }

    private LinearLayout deviceRow(BluetoothDevice device, boolean likelyAirPods) {
        LinearLayout row = panel();
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView name = label(deviceName(device), 17, Typeface.BOLD, text);
        row.addView(name);

        String battery = batteryText(device);
        String connection = isConnected(device) ? "Connected" : "Paired";
        int chipColor = isConnected(device) ? accent : muted;
        TextView status = label(connection + "  ·  " + battery, 14, Typeface.BOLD, chipColor);
        status.setPadding(0, dp(5), 0, 0);
        row.addView(status);

        String detail = likelyAirPods
                ? "Likely Apple earbuds. Battery may show as unknown unless Android receives a battery event from the buds."
                : "Other paired Bluetooth device. Kept visible so you can compare routing state.";
        TextView extra = label(detail, 13, Typeface.NORMAL, muted);
        extra.setPadding(0, dp(6), 0, 0);
        row.addView(extra);

        return row;
    }

    private LinearLayout emptyPanel(String message) {
        LinearLayout box = panel();
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.addView(label(message, 14, Typeface.NORMAL, muted));
        return box;
    }

    private String profileSummary() {
        if (!hasBluetoothConnectPermission()) {
            return "Profiles: permission required";
        }
        StringBuilder builder = new StringBuilder("Profiles: ");
        int a2dpCount = countProfile(BluetoothProfile.A2DP);
        int headsetCount = countProfile(BluetoothProfile.HEADSET);
        builder.append("media ").append(a2dpCount > 0 ? "connected" : "idle");
        builder.append(", calls ").append(headsetCount > 0 ? "connected" : "idle");
        return builder.toString();
    }

    private String audioSummary() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return "Audio route: unavailable";
        }
        boolean musicActive = audioManager.isMusicActive();
        String mode;
        switch (audioManager.getMode()) {
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
                mode = "call/communication";
                break;
            case AudioManager.MODE_RINGTONE:
                mode = "ringtone";
                break;
            default:
                mode = "normal";
                break;
        }
        return "Audio: " + (musicActive ? "media active" : "media idle") + ", phone mode " + mode;
    }

    private int countProfile(int profile) {
        int count = 0;
        for (Set<Integer> profiles : connectedProfiles.values()) {
            if (profiles.contains(profile)) {
                count++;
            }
        }
        return count;
    }

    private void collectConnectedProfiles(int profile, BluetoothProfile proxy) {
        if (proxy == null || !hasBluetoothConnectPermission()) {
            return;
        }
        try {
            for (BluetoothDevice device : proxy.getConnectedDevices()) {
                String address = device.getAddress();
                Set<Integer> set = connectedProfiles.get(address);
                if (set == null) {
                    set = new HashSet<>();
                    connectedProfiles.put(address, set);
                }
                set.add(profile);
            }
        } catch (SecurityException ignored) {
            // Permission can be revoked while profile callbacks are active.
        }
    }

    private boolean isConnected(BluetoothDevice device) {
        if (device == null || !hasBluetoothConnectPermission()) {
            return false;
        }
        try {
            Set<Integer> profiles = connectedProfiles.get(device.getAddress());
            return profiles != null && !profiles.isEmpty();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private String batteryText(BluetoothDevice device) {
        Integer broadcastLevel = batteryByAddress.get(addressSafely(device));
        if (broadcastLevel != null && broadcastLevel >= 0) {
            return "Battery " + broadcastLevel + "%";
        }
        int level = readBatteryLevel(device);
        if (level >= 0) {
            batteryByAddress.put(addressSafely(device), level);
            return "Battery " + level + "%";
        }
        return "Battery unknown";
    }

    private void updateWidgetBatterySummary() {
        if (widgetBatteryStatus == null) {
            return;
        }
        AirPodsBatteryStore.Snapshot snapshot = AirPodsBatteryStore.read(this);
        widgetBatteryStatus.setText("Widget battery: " + snapshot.detailText());
        if (experimentalStatus != null) {
            experimentalStatus.setText("Experimental: " + snapshot.stateText()
                    + " · auto-pause " + (autoPauseEnabled ? "on" : "off"));
        }
        if (hardwareStatus != null) {
            hardwareStatus.setText(snapshot.hardwareText());
        }
    }

    private void handleBatteryBroadcast(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        int level = intent.getIntExtra(EXTRA_BATTERY, -1);
        if (device == null || !hasBluetoothConnectPermission()) {
            return;
        }
        try {
            batteryByAddress.put(device.getAddress(), level);
            int left = extraBattery(intent, "left");
            int right = extraBattery(intent, "right");
            int caseBattery = extraBattery(intent, "case");
            boolean hasSplit = left >= 0 || right >= 0 || caseBattery >= 0;
            AirPodsBatteryStore.write(this, new AirPodsBatteryStore.Snapshot(
                    deviceName(device),
                    left,
                    right,
                    caseBattery,
                    validBattery(level) ? level : -1,
                    System.currentTimeMillis(),
                    hasSplit ? "Android battery extras" : "Android battery",
                    hasSplit
                            ? "Android exposed separate battery values."
                            : "Android exposed one combined Bluetooth battery value."
            ));
        } catch (SecurityException ignored) {
            // Permission can be revoked while the app is open.
        }
    }

    private int extraBattery(Intent intent, String part) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return -1;
        }
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (!(value instanceof Integer)) {
                continue;
            }
            String lower = key.toLowerCase(Locale.US);
            if (lower.contains(part)) {
                int percent = (Integer) value;
                if (validBattery(percent)) {
                    return percent;
                }
            }
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private void startAirPodsBatteryScanIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !hasBluetoothScanPermission()
                || bluetoothAdapter == null
                || !bluetoothAdapter.isEnabled()) {
            AirPodsBatteryStore.updateWidgets(this);
            return;
        }
        if (airPodsScanActive) {
            return;
        }
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            return;
        }
        airPodsScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    handleScanResult(result);
                }
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            bleScanner.startScan(null, settings, airPodsScanCallback);
            airPodsScanActive = true;
            handler.postDelayed(this::stopAirPodsBatteryScan, AIRPODS_SCAN_MS);
        } catch (SecurityException ignored) {
            airPodsScanActive = false;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopAirPodsBatteryScan() {
        if (!airPodsScanActive || bleScanner == null || airPodsScanCallback == null) {
            return;
        }
        try {
            bleScanner.stopScan(airPodsScanCallback);
        } catch (SecurityException ignored) {
            // Permission can be revoked while the scan is active.
        }
        airPodsScanActive = false;
        airPodsScanCallback = null;
    }

    private void handleScanResult(ScanResult result) {
        if (result == null) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        if (record == null) {
            return;
        }
        byte[] appleData = record.getManufacturerSpecificData(APPLE_COMPANY_ID);
        AirPodsBatteryStore.Snapshot snapshot = parseAirPodsManufacturerData(deviceNameFromScan(result, record), appleData);
        if (snapshot != null) {
            handleExperimentalEarActions(snapshot);
            AirPodsBatteryStore.write(this, snapshot);
            updateWidgetBatterySummary();
        }
    }

    private String deviceNameFromScan(ScanResult result, ScanRecord record) {
        String recordName = record.getDeviceName();
        if (!TextUtils.isEmpty(recordName)) {
            return recordName;
        }
        if (hasBluetoothConnectPermission()) {
            try {
                String name = result.getDevice().getName();
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            } catch (SecurityException ignored) {
                // Permission can be revoked while the scan result is handled.
            }
        }
        return "AirPods";
    }

    private AirPodsBatteryStore.Snapshot parseAirPodsManufacturerData(String name, byte[] data) {
        if (data == null || data.length < 11) {
            return null;
        }
        for (int offset = 0; offset <= data.length - 11; offset++) {
            if ((data[offset] & 0xFF) == 0x07) {
                int payloadLength = data[offset + 1] & 0xFF;
                int publicStart = offset + 2;
                if (payloadLength < 9 || (data[publicStart] & 0xFF) != 0x01) {
                    continue;
                }
                int modelCode = ((data[publicStart + 1] & 0xFF) << 8) | (data[publicStart + 2] & 0xFF);
                int status = data[publicStart + 3] & 0xFF;
                int podBattery = data[publicStart + 4] & 0xFF;
                int flagsAndCase = data[publicStart + 5] & 0xFF;
                int lidRaw = data[publicStart + 6] & 0xFF;

                boolean valuesFlipped = !bit(status, 5);
                boolean thisPodInCase = bit(status, 6);
                boolean onePodInCase = bit(status, 4);
                boolean bothPodsInCase = bit(status, 2);
                boolean leftInEar = (valuesFlipped ^ thisPodInCase) ? bit(status, 3) : bit(status, 1);
                boolean rightInEar = (valuesFlipped ^ thisPodInCase) ? bit(status, 1) : bit(status, 3);

                int left = nibbleToPercent(valuesFlipped ? upperNibble(podBattery) : lowerNibble(podBattery));
                int right = nibbleToPercent(valuesFlipped ? lowerNibble(podBattery) : upperNibble(podBattery));
                int caseBattery = nibbleToPercent(lowerNibble(flagsAndCase));
                int flags = upperNibble(flagsAndCase);
                int leftCharging = boolInt(valuesFlipped ? bit(flags, 1) : bit(flags, 0));
                int rightCharging = boolInt(valuesFlipped ? bit(flags, 0) : bit(flags, 1));
                int caseCharging = boolInt(bit(flags, 2));
                String lidState = lidState(lidRaw, thisPodInCase || onePodInCase || bothPodsInCase, thisPodInCase || bothPodsInCase);
                String microphone = microphoneSide(valuesFlipped, thisPodInCase);
                String inCaseState = inCaseState(thisPodInCase, onePodInCase, bothPodsInCase);
                if (left >= 0 || right >= 0 || caseBattery >= 0) {
                    return new AirPodsBatteryStore.Snapshot(
                            name,
                            left,
                            right,
                            caseBattery,
                            -1,
                            System.currentTimeMillis(),
                            "AirPods BLE",
                            "BLE advertisement values are exposed in 10% steps.",
                            boolInt(leftInEar),
                            boolInt(rightInEar),
                            lidState,
                            leftCharging,
                            rightCharging,
                            caseCharging,
                            modelName(modelCode),
                            microphone,
                            inCaseState
                    );
                }
            }
        }
        return null;
    }

    private void handleExperimentalEarActions(AirPodsBatteryStore.Snapshot snapshot) {
        handleCaseOpenPopup(snapshot);
        if (!autoPauseEnabled || snapshot.leftInEar < 0 || snapshot.rightInEar < 0) {
            return;
        }
        boolean worn = snapshot.leftInEar == 1 || snapshot.rightInEar == 1;
        if (lastWornState == null) {
            lastWornState = worn;
            return;
        }
        if (lastWornState && !worn) {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null && audioManager.isMusicActive()) {
                dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE);
                autoPausedByApp = true;
            }
        } else if (!lastWornState && worn && autoPausedByApp) {
            dispatchMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY);
            autoPausedByApp = false;
        }
        lastWornState = worn;
    }

    private void handleCaseOpenPopup(AirPodsBatteryStore.Snapshot snapshot) {
        if (!casePopupEnabled || !"Open".equals(snapshot.lidState)) {
            lastLidState = snapshot.lidState;
            return;
        }
        long now = System.currentTimeMillis();
        if (!"Open".equals(lastLidState) && now - lastCasePopupAt > 15000L && !isFinishing()) {
            lastCasePopupAt = now;
            new AlertDialog.Builder(this)
                    .setTitle(snapshot.modelTitle())
                    .setMessage(snapshot.detailText() + "\n" + snapshot.stateText() + "\n" + snapshot.hardwareText())
                    .setPositiveButton("OK", null)
                    .show();
        }
        lastLidState = snapshot.lidState;
    }

    private void dispatchMediaButton(int keyCode) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private String lidState(int raw, boolean hasCaseContext, boolean reliable) {
        if (!hasCaseContext) {
            return "Not in case";
        }
        if (!reliable) {
            return "Unknown";
        }
        return bit(raw, 3) ? "Closed" : "Open";
    }

    private String microphoneSide(boolean valuesFlipped, boolean thisPodInCase) {
        boolean leftMic = !valuesFlipped ^ thisPodInCase;
        return leftMic ? "Left" : "Right";
    }

    private String inCaseState(boolean thisPodInCase, boolean onePodInCase, boolean bothPodsInCase) {
        if (bothPodsInCase) {
            return "both in";
        }
        if (thisPodInCase || onePodInCase) {
            return "one in";
        }
        return "not in";
    }

    private String modelName(int modelCode) {
        switch (modelCode) {
            case 0x0220:
                return "AirPods 1";
            case 0x0F20:
                return "AirPods 2";
            case 0x1320:
                return "AirPods 3";
            case 0x1920:
                return "AirPods 4";
            case 0x1B20:
                return "AirPods 4 ANC";
            case 0x0E20:
                return "AirPods Pro";
            case 0x1420:
                return "AirPods Pro 2";
            case 0x2420:
                return "AirPods Pro 2 USB-C";
            case 0x2720:
                return "AirPods Pro 3";
            case 0x0A20:
                return "AirPods Max";
            case 0x1F20:
                return "AirPods Max USB-C";
            case 0x2D20:
                return "AirPods Max 2";
            case 0x1220:
                return "Beats Fit Pro";
            case 0x1020:
                return "Beats Flex";
            case 0x1120:
                return "Beats Studio Buds";
            case 0x1620:
                return "Beats Studio Buds+";
            case 0x1720:
                return "Beats Studio Pro";
            case 0x2620:
                return "Beats Solo Buds";
            default:
                return String.format(Locale.US, "Unknown model 0x%04X", modelCode);
        }
    }

    private boolean bit(int value, int bit) {
        return ((value >> bit) & 0x01) == 1;
    }

    private int lowerNibble(int value) {
        return value & 0x0F;
    }

    private int upperNibble(int value) {
        return (value >> 4) & 0x0F;
    }

    private int boolInt(boolean value) {
        return value ? 1 : 0;
    }

    private int nibbleToPercent(int nibble) {
        return nibble >= 0 && nibble <= 10 ? nibble * 10 : -1;
    }

    private boolean validBattery(int level) {
        return level >= 0 && level <= 100;
    }

    private int readBatteryLevel(BluetoothDevice device) {
        if (device == null || !hasBluetoothConnectPermission()) {
            return -1;
        }
        try {
            Method method = device.getClass().getMethod("getBatteryLevel");
            Object value = method.invoke(device);
            if (value instanceof Integer) {
                int level = (Integer) value;
                return level >= 0 && level <= 100 ? level : -1;
            }
        } catch (Exception ignored) {
            // Some Android builds do not expose Bluetooth battery level through public reflection.
        }
        return -1;
    }

    private String addressSafely(BluetoothDevice device) {
        if (device == null || !hasBluetoothConnectPermission()) {
            return "";
        }
        try {
            return device.getAddress();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private String deviceName(BluetoothDevice device) {
        if (device == null || !hasBluetoothConnectPermission()) {
            return "Unknown device";
        }
        try {
            String name = device.getName();
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        } catch (SecurityException ignored) {
            return "Unknown device";
        }
        return "Bluetooth device";
    }

    private boolean isAppleEarbud(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.contains("airpods")
                || lower.contains("air pods")
                || lower.contains("beats")
                || lower.contains("powerbeats")
                || lower.contains("beats fit");
    }

    @SuppressLint("MissingPermission")
    private List<BluetoothDevice> getBondedDevicesSafely() {
        List<BluetoothDevice> devices = new ArrayList<>();
        if (bluetoothAdapter == null || !hasBluetoothConnectPermission()) {
            return devices;
        }
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                devices.addAll(bonded);
            }
        } catch (SecurityException ignored) {
            // Permission can be revoked while the app is open.
        }
        return devices;
    }

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    @SuppressLint("MissingPermission")
    private void bindProfileProxies() {
        if (bluetoothAdapter == null || !hasBluetoothConnectPermission()) {
            return;
        }
        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET);
    }

    private void closeProfileProxy(int profile, BluetoothProfile proxy) {
        if (bluetoothAdapter != null && proxy != null) {
            bluetoothAdapter.closeProfileProxy(profile, proxy);
        }
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothScanPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (!hasBluetoothConnectPermission() || !hasBluetoothScanPermission())) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    REQUEST_BLUETOOTH_CONNECT
            );
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void showAbout() {
        String version = "Version: " + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE;
        String message = "Developer: Andrei Efremuahkin\n"
                + "Email: andrei.efr@gmail.com\n"
                + "GitHub: " + REPO_URL + "\n"
                + version;
        new AlertDialog.Builder(this)
                .setTitle("About AirPods Companion")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private TextView label(String value, int sp, int style, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        textView.setLineSpacing(dp(2), 1.0f);
        return textView;
    }

    private TextView paragraph(String value) {
        TextView paragraph = label(value, 14, Typeface.NORMAL, muted);
        paragraph.setPadding(0, dp(8), 0, 0);
        return paragraph;
    }

    private Button smallButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(18);
        button.setTextColor(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(buttonBackground(buttonBg, dp(16)));
        return button;
    }

    private Button actionButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(text);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setBackground(buttonBackground(buttonBg, dp(12)));
        return button;
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(buttonBackground(panel, dp(8)));
        return layout;
    }

    private GradientDrawable buttonBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams blockMargins() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(10), 0, dp(10));
        return params;
    }

    private LinearLayout.LayoutParams tightMargins() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams fixedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(8), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weightedAction() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.setMargins(0, 0, dp(8), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

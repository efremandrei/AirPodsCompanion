package com.andre.airpodscompanion;

import android.Manifest;
import android.animation.ValueAnimator;
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
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
    private static final String PREF_DIAG_APPLE_FRAMES = "diag_apple_frames";
    private static final String PREF_DIAG_SCAN_RESULTS = "diag_scan_results";
    private static final String PREF_DIAG_CONTINUITY_FRAMES = "diag_continuity_frames";
    private static final String PREF_DIAG_DECODED_FRAMES = "diag_decoded_frames";
    private static final String PREF_DIAG_LAST = "diag_last";
    private static final String PREF_DIAG_LAST_APPLE = "diag_last_apple";
    private static final String PREF_DIAG_UPDATED = "diag_updated";
    private static final String PREF_NOISE_STATUS = "noise_status";
    private static final String PREF_LAST_SEEN_TIME = "last_seen_time";
    private static final String PREF_LAST_SEEN_REASON = "last_seen_reason";
    private static final String PREF_LAST_SEEN_LAT = "last_seen_lat";
    private static final String PREF_LAST_SEEN_LON = "last_seen_lon";
    private static final String PREF_LAST_SEEN_PROVIDER = "last_seen_provider";
    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final String REPO_URL = "https://github.com/efremandrei/AirPodsCompanion";
    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final long AIRPODS_SCAN_MS = 60000L;

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
    private TextView noiseControlStatus;
    private TextView scanDiagnosticStatus;
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

        LinearLayout visualPanel = panel();
        visualPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.addView(visualPanel, blockMargins());
        visualPanel.addView(label("AirPods visual", 18, Typeface.BOLD, text));
        TextView visualSummary = label("Animated case, earbuds, battery, and last-seen location from this phone.", 13, Typeface.NORMAL, muted);
        visualSummary.setPadding(0, dp(6), 0, dp(10));
        visualPanel.addView(visualSummary);
        visualPanel.addView(new AnimatedAirPodsView(this, darkMode, text, muted, accent, warning), matchWrap());

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
        noiseControlStatus = label("", 14, Typeface.NORMAL, muted);
        noiseControlStatus.setPadding(0, dp(6), 0, 0);
        summary.addView(noiseControlStatus);
        scanDiagnosticStatus = label("", 12, Typeface.NORMAL, muted);
        scanDiagnosticStatus.setPadding(0, dp(8), 0, 0);
        summary.addView(scanDiagnosticStatus);
        lastUpdated = label("", 12, Typeface.NORMAL, muted);
        lastUpdated.setPadding(0, dp(10), 0, 0);
        summary.addView(lastUpdated);

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
        for (BluetoothDevice device : bonded) {
            if (isAppleEarbud(deviceName(device))) {
                airPods.add(device);
            }
        }

        boolean permission = hasBluetoothConnectPermission();
        boolean bluetoothAvailable = bluetoothAdapter != null;
        boolean enabled = bluetoothAvailable && bluetoothAdapter.isEnabled();
        boolean anyAirPodsConnected = false;
        for (BluetoothDevice device : airPods) {
            if (isConnected(device)) {
                anyAirPodsConnected = true;
                recordAirPodsSeen("Bluetooth audio");
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

        if (airPods.isEmpty()) {
            deviceList.addView(emptyPanel("No paired Apple earphones or headphones are visible to this app."));
            return;
        }

        for (BluetoothDevice device : airPods) {
            deviceList.addView(deviceRow(device), blockMargins());
        }
    }

    private LinearLayout deviceRow(BluetoothDevice device) {
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

        String detail = "Apple earphones/headphones only. Battery may show as unknown unless Android receives a battery event from the device.";
        TextView extra = label(detail, 13, Typeface.NORMAL, muted);
        extra.setPadding(0, dp(6), 0, 0);
        row.addView(extra);

        if (isConnected(device)) {
            TextView noiseTitle = label("Noise control", 14, Typeface.BOLD, text);
            noiseTitle.setPadding(0, dp(12), 0, 0);
            row.addView(noiseTitle);
            row.addView(noiseModeRow(device, "ANC", AacpNoiseControl.MODE_ANC, "Transp.", AacpNoiseControl.MODE_TRANSPARENCY));
            row.addView(noiseModeRow(device, "Adaptive", AacpNoiseControl.MODE_ADAPTIVE, "Off", AacpNoiseControl.MODE_OFF));
        }

        return row;
    }

    private LinearLayout noiseModeRow(BluetoothDevice device, String leftLabel, int leftMode, String rightLabel, int rightMode) {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(8), 0, 0);

        Button left = actionButton(leftLabel);
        left.setOnClickListener(v -> sendNoiseMode(device, leftLabel, leftMode));
        controls.addView(left, weightedAction());

        Button right = actionButton(rightLabel);
        right.setOnClickListener(v -> sendNoiseMode(device, rightLabel, rightMode));
        controls.addView(right, weightedAction());

        return controls;
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
        if (scanDiagnosticStatus != null) {
            scanDiagnosticStatus.setText(scanDiagnosticSummary());
        }
        if (noiseControlStatus != null) {
            String status = preferences.getString(PREF_NOISE_STATUS, "Noise control: ready when AirPods are connected");
            noiseControlStatus.setText(status);
        }
    }

    private void sendNoiseMode(BluetoothDevice device, String label, int mode) {
        if (device == null || !hasBluetoothConnectPermission()) {
            setNoiseStatus("Noise control: Bluetooth permission required");
            return;
        }
        AirPodsBatteryStore.Snapshot snapshot = AirPodsBatteryStore.read(this);
        boolean connected = isConnected(device) || isDeviceConnectedDirect(device);
        if (!connected) {
            setNoiseStatus("Noise control: AirPods are not connected over Bluetooth audio");
            return;
        }
        if ("both in".equals(snapshot.inCaseState)) {
            setNoiseStatus("Noise control: sending " + label + " despite stale case BLE");
        }
        setNoiseStatus("Noise control: sending " + label);
        new Thread(() -> {
            try {
                AacpNoiseControl.setMode(device, mode);
                runOnUiThread(() -> setNoiseStatus("Noise control: " + label + " command sent"));
            } catch (Exception error) {
                runOnUiThread(() -> setNoiseStatus("Noise control failed: " + shortError(error)));
            }
        }, "airpods-aacp-noise").start();
    }

    private void setNoiseStatus(String status) {
        preferences.edit().putString(PREF_NOISE_STATUS, status).apply();
        if (noiseControlStatus != null) {
            noiseControlStatus.setText(status);
        }
    }

    private String shortError(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (TextUtils.isEmpty(message)) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }

    private boolean isDeviceConnectedDirect(BluetoothDevice device) {
        try {
            Method method = device.getClass().getMethod("isConnected");
            Object value = method.invoke(device);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
            return false;
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
            recordAirPodsSeen("Android battery event");
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
        if (!hasBluetoothScanPermission()
                || bluetoothAdapter == null
                || !bluetoothAdapter.isEnabled()) {
            recordScanDiagnosticMessage("Scan skipped: Bluetooth scan permission, adapter, or enabled state is missing.");
            AirPodsBatteryStore.updateWidgets(this);
            return;
        }
        if (airPodsScanActive) {
            recordScanDiagnosticMessage("Scan already active.");
            return;
        }
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            recordScanDiagnosticMessage("Scan skipped: Android did not provide a BLE scanner.");
            return;
        }
        resetScanDiagnostics();
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

            @Override
            public void onScanFailed(int errorCode) {
                airPodsScanActive = false;
                recordScanDiagnosticMessage("Scan failed: Android BLE scanner error " + errorCode + ".");
                updateWidgetBatterySummary();
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            bleScanner.startScan(null, settings, airPodsScanCallback);
            airPodsScanActive = true;
            recordScanDiagnosticMessage("Scan active: waiting 60 seconds for Apple Continuity frames.");
            updateWidgetBatterySummary();
            handler.postDelayed(this::stopAirPodsBatteryScan, AIRPODS_SCAN_MS);
        } catch (SecurityException ignored) {
            airPodsScanActive = false;
            recordScanDiagnosticMessage("Scan failed: Bluetooth scan permission was rejected at runtime.");
            updateWidgetBatterySummary();
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
        if (preferences.getLong(PREF_DIAG_SCAN_RESULTS, 0L) == 0L) {
            recordScanDiagnosticMessage("Scan finished: Android delivered no BLE scan callbacks.");
        } else if (preferences.getLong(PREF_DIAG_APPLE_FRAMES, 0L) == 0L) {
            recordScanDiagnosticMessage("Scan finished: BLE callbacks arrived, but no Apple manufacturer frames were delivered.");
        }
        updateWidgetBatterySummary();
    }

    private void handleScanResult(ScanResult result) {
        if (result == null) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        if (record == null) {
            return;
        }
        recordScanResultDiagnostic(record);
        byte[] appleData = record.getManufacturerSpecificData(APPLE_COMPANY_ID);
        if (appleData != null) {
            recordAppleScanDiagnostic(appleData, false, "Apple manufacturer data");
        }
        AirPodsBatteryStore.Snapshot snapshot = parseAirPodsManufacturerData(deviceNameFromScan(result, record), appleData);
        if (snapshot != null) {
            handleExperimentalEarActions(snapshot);
            AirPodsBatteryStore.write(this, snapshot);
            recordAirPodsSeen("AirPods BLE");
            updateWidgetBatterySummary();
        }
    }

    @SuppressLint("MissingPermission")
    private void recordAirPodsSeen(String reason) {
        long now = System.currentTimeMillis();
        long previous = preferences.getLong(PREF_LAST_SEEN_TIME, 0L);
        if (now - previous < 30000L) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(PREF_LAST_SEEN_TIME, now)
                .putString(PREF_LAST_SEEN_REASON, reason);
        Location location = lastKnownLocation();
        if (location != null) {
            editor.putString(PREF_LAST_SEEN_LAT, String.format(Locale.US, "%.5f", location.getLatitude()))
                    .putString(PREF_LAST_SEEN_LON, String.format(Locale.US, "%.5f", location.getLongitude()))
                    .putString(PREF_LAST_SEEN_PROVIDER, location.getProvider());
        }
        editor.apply();
    }

    @SuppressLint("MissingPermission")
    private Location lastKnownLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        Location best = null;
        for (String provider : manager.getProviders(true)) {
            Location candidate;
            try {
                candidate = manager.getLastKnownLocation(provider);
            } catch (Exception ignored) {
                continue;
            }
            if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                best = candidate;
            }
        }
        return best;
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
                int publicPrefix = data[publicStart] & 0xFF;
                if (payloadLength < 9 || (publicPrefix != 0x01 && publicPrefix != 0x07)) {
                    continue;
                }
                int modelCode = ((data[publicStart + 1] & 0xFF) << 8) | (data[publicStart + 2] & 0xFF);
                int status = data[publicStart + 3] & 0xFF;
                int podBattery = data[publicStart + 4] & 0xFF;
                int flagsAndCase = data[publicStart + 5] & 0xFF;
                int lidRaw = data[publicStart + 6] & 0xFF;
                String decodedModel = modelName(modelCode);
                recordAppleScanDiagnostic(data, true, decodedModel + " prefix 0x" + String.format(Locale.US, "%02X", publicPrefix));

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
                            decodedModel,
                            microphone,
                            inCaseState
                    );
                }
            }
        }
        return null;
    }

    private void recordAppleScanDiagnostic(byte[] data, boolean continuity, String detail) {
        SharedPreferences.Editor editor = preferences.edit();
        long appleFrames = preferences.getLong(PREF_DIAG_APPLE_FRAMES, 0L) + (continuity ? 0L : 1L);
        long continuityFrames = preferences.getLong(PREF_DIAG_CONTINUITY_FRAMES, 0L) + (continuity ? 1L : 0L);
        long decodedFrames = preferences.getLong(PREF_DIAG_DECODED_FRAMES, 0L);
        if (continuity && detail != null && !detail.startsWith("Unknown model")) {
            decodedFrames += 1L;
        }
        editor.putLong(PREF_DIAG_APPLE_FRAMES, appleFrames);
        editor.putLong(PREF_DIAG_CONTINUITY_FRAMES, continuityFrames);
        editor.putLong(PREF_DIAG_DECODED_FRAMES, decodedFrames);
        editor.putLong(PREF_DIAG_UPDATED, System.currentTimeMillis());
        String appleMessage = "BLE Apple frames " + appleFrames
                + ", Continuity " + continuityFrames
                + ", decoded " + decodedFrames
                + " · len " + data.length
                + " · public " + publicContinuityHex(data)
                + " · " + detail;
        editor.putString(PREF_DIAG_LAST, appleMessage);
        editor.putString(PREF_DIAG_LAST_APPLE, appleMessage);
        editor.apply();
    }

    private void resetScanDiagnostics() {
        preferences.edit()
                .putLong(PREF_DIAG_SCAN_RESULTS, 0L)
                .putLong(PREF_DIAG_APPLE_FRAMES, 0L)
                .putLong(PREF_DIAG_CONTINUITY_FRAMES, 0L)
                .putLong(PREF_DIAG_DECODED_FRAMES, 0L)
                .putLong(PREF_DIAG_UPDATED, System.currentTimeMillis())
                .putString(PREF_DIAG_LAST, "Scan preparing.")
                .putString(PREF_DIAG_LAST_APPLE, "")
                .apply();
    }

    private void recordScanResultDiagnostic(ScanRecord record) {
        long scanResults = preferences.getLong(PREF_DIAG_SCAN_RESULTS, 0L) + 1L;
        preferences.edit()
                .putLong(PREF_DIAG_SCAN_RESULTS, scanResults)
                .putLong(PREF_DIAG_UPDATED, System.currentTimeMillis())
                .putString(PREF_DIAG_LAST, "BLE callbacks " + scanResults
                        + ", manufacturer sections " + record.getManufacturerSpecificData().size()
                        + ".")
                .apply();
    }

    private void recordScanDiagnosticMessage(String message) {
        preferences.edit()
                .putLong(PREF_DIAG_UPDATED, System.currentTimeMillis())
                .putString(PREF_DIAG_LAST, message)
                .apply();
    }

    private String scanDiagnosticSummary() {
        String last = preferences.getString(PREF_DIAG_LAST, "");
        if (TextUtils.isEmpty(last)) {
            return airPodsScanActive
                    ? "BLE diagnostic: scanning for Apple Continuity frames"
                    : "BLE diagnostic: no Apple frames captured yet";
        }
        long updated = preferences.getLong(PREF_DIAG_UPDATED, 0L);
        String time = updated > 0
                ? DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(updated))
                : "unknown time";
        long scanResults = preferences.getLong(PREF_DIAG_SCAN_RESULTS, 0L);
        String lastApple = preferences.getString(PREF_DIAG_LAST_APPLE, "");
        String applePart = TextUtils.isEmpty(lastApple) ? "" : " · last Apple: " + lastApple;
        return "BLE diagnostic: " + last + " · callbacks " + scanResults + applePart + " · " + time;
    }

    private String publicContinuityHex(byte[] data) {
        int count = Math.min(data.length, 11);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        if (data.length > count) {
            builder.append(" ...");
        }
        return builder.toString();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (!hasBluetoothConnectPermission() || !hasBluetoothScanPermission())) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_BLUETOOTH_CONNECT
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasBluetoothScanPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLUETOOTH_CONNECT);
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
        SpannableString linkedMessage = new SpannableString(message);
        Linkify.addLinks(linkedMessage, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("About AirPods Companion")
                .setMessage(linkedMessage)
                .setNeutralButton("Check for updates", (dialogInterface, which) -> AppUpdateChecker.checkNow(this))
                .setPositiveButton("OK", null)
                .show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
            messageView.setLinksClickable(true);
        }
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

    private static final class AnimatedAirPodsView extends View {
        private final Context context;
        private final boolean darkMode;
        private final int textColor;
        private final int mutedColor;
        private final int accentColor;
        private final int warningColor;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private ValueAnimator animator;
        private float phase;

        AnimatedAirPodsView(Context context, boolean darkMode, int textColor, int mutedColor, int accentColor, int warningColor) {
            super(context);
            this.context = context.getApplicationContext();
            this.darkMode = darkMode;
            this.textColor = textColor;
            this.mutedColor = mutedColor;
            this.accentColor = accentColor;
            this.warningColor = warningColor;
            this.density = context.getResources().getDisplayMetrics().density;
            setContentDescription("Animated AirPods visual showing case, earbuds, battery levels, and the last place this phone saw the AirPods.");
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2200L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int desiredHeight = Math.round(dp(width < dp(360) ? 460 : 430));
            setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            AirPodsBatteryStore.Snapshot snapshot = AirPodsBatteryStore.read(context);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);

            float width = getWidth();
            float top = dp(6);
            float midX = width / 2f;
            float bob = (float) Math.sin(phase * Math.PI * 2f) * dp(5);

            drawCase(canvas, midX, top + dp(86), snapshot);
            drawEarbud(canvas, midX - dp(112), top + dp(50) + bob, true, batteryValue(snapshot.left, snapshot.combined), snapshot.leftCharging == 1);
            drawEarbud(canvas, midX + dp(112), top + dp(50) - bob, false, batteryValue(snapshot.right, snapshot.combined), snapshot.rightCharging == 1);
            drawBatteryStrip(canvas, dp(8), top + dp(224), width - dp(16), snapshot);
            drawLocation(canvas, dp(8), top + dp(338), width - dp(16), prefs);
        }

        private void drawCase(Canvas canvas, float cx, float cy, AirPodsBatteryStore.Snapshot snapshot) {
            float w = Math.min(getWidth() - dp(32), dp(230));
            float h = dp(118);
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            fillPaint.setColor(darkMode ? Color.rgb(236, 240, 242) : Color.rgb(255, 255, 255));
            canvas.drawRoundRect(rect, dp(30), dp(30), fillPaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(darkMode ? Color.rgb(128, 145, 154) : Color.rgb(185, 196, 204));
            canvas.drawRoundRect(rect, dp(30), dp(30), strokePaint);

            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(Color.rgb(177, 187, 194));
            canvas.drawLine(rect.left + dp(24), rect.top + dp(43), rect.right - dp(24), rect.top + dp(43), strokePaint);
            fillPaint.setColor(snapshot.caseCharging == 1 ? accentColor : Color.rgb(144, 154, 162));
            canvas.drawCircle(cx, rect.top + dp(73), dp(5), fillPaint);
            drawCenteredText(canvas, percentText(snapshot.caseBattery), cx, rect.bottom - dp(18), Color.rgb(48, 55, 60), 13, true);
        }

        private void drawEarbud(Canvas canvas, float cx, float cy, boolean left, int battery, boolean charging) {
            fillPaint.setColor(darkMode ? Color.rgb(245, 247, 249) : Color.rgb(255, 255, 255));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(darkMode ? Color.rgb(136, 150, 158) : Color.rgb(185, 196, 204));

            rect.set(cx - dp(24), cy - dp(30), cx + dp(24), cy + dp(18));
            canvas.drawRoundRect(rect, dp(24), dp(24), fillPaint);
            canvas.drawRoundRect(rect, dp(24), dp(24), strokePaint);
            float stemLeft = left ? cx + dp(4) : cx - dp(16);
            rect.set(stemLeft, cy + dp(4), stemLeft + dp(12), cy + dp(72));
            canvas.drawRoundRect(rect, dp(7), dp(7), fillPaint);
            canvas.drawRoundRect(rect, dp(7), dp(7), strokePaint);

            float ringRadius = dp(32);
            strokePaint.setStrokeWidth(dp(5));
            strokePaint.setColor(darkMode ? Color.rgb(58, 68, 74) : Color.rgb(224, 231, 235));
            canvas.drawCircle(cx, cy - dp(4), ringRadius, strokePaint);
            strokePaint.setColor(battery >= 0 ? accentColor : warningColor);
            rect.set(cx - ringRadius, cy - dp(4) - ringRadius, cx + ringRadius, cy - dp(4) + ringRadius);
            float sweep = battery >= 0 ? Math.max(10f, battery * 3.6f) : 45f + phase * 90f;
            canvas.drawArc(rect, -90f, sweep, false, strokePaint);
            drawCenteredText(canvas, (left ? "L " : "R ") + percentText(battery), cx, cy + dp(102), textColor, 12, true);
            if (charging) {
                drawCenteredText(canvas, "charging", cx, cy + dp(118), accentColor, 10, false);
            }
        }

        private void drawBatteryStrip(Canvas canvas, float left, float top, float width, AirPodsBatteryStore.Snapshot snapshot) {
            drawBatteryBar(canvas, left, top, width, "Left", batteryValue(snapshot.left, snapshot.combined), snapshot.leftCharging == 1);
            drawBatteryBar(canvas, left, top + dp(34), width, "Right", batteryValue(snapshot.right, snapshot.combined), snapshot.rightCharging == 1);
            drawBatteryBar(canvas, left, top + dp(68), width, "Case", snapshot.caseBattery, snapshot.caseCharging == 1);
        }

        private void drawBatteryBar(Canvas canvas, float left, float top, float width, String label, int value, boolean charging) {
            float labelWidth = dp(58);
            drawText(canvas, label, left, top + dp(20), textColor, 12, true);
            rect.set(left + labelWidth, top + dp(5), left + width - dp(58), top + dp(24));
            fillPaint.setColor(darkMode ? Color.rgb(45, 56, 64) : Color.rgb(224, 231, 235));
            canvas.drawRoundRect(rect, dp(10), dp(10), fillPaint);
            if (value >= 0) {
                RectF fill = new RectF(rect.left, rect.top, rect.left + rect.width() * (value / 100f), rect.bottom);
                fillPaint.setColor(accentColor);
                canvas.drawRoundRect(fill, dp(10), dp(10), fillPaint);
            }
            drawText(canvas, percentText(value) + (charging ? " +" : ""), left + width - dp(48), top + dp(20), mutedColor, 12, false);
        }

        private void drawLocation(Canvas canvas, float left, float top, float width, SharedPreferences prefs) {
            long seenAt = prefs.getLong(PREF_LAST_SEEN_TIME, 0L);
            String reason = prefs.getString(PREF_LAST_SEEN_REASON, "not seen yet");
            String lat = prefs.getString(PREF_LAST_SEEN_LAT, "");
            String lon = prefs.getString(PREF_LAST_SEEN_LON, "");
            String provider = prefs.getString(PREF_LAST_SEEN_PROVIDER, "");

            float pinX = left + dp(34);
            float pinY = top + dp(38);
            float pulse = dp(12) + phase * dp(18);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(alpha(accentColor, 90));
            canvas.drawCircle(pinX, pinY, pulse, strokePaint);
            fillPaint.setColor(accentColor);
            canvas.drawCircle(pinX, pinY - dp(7), dp(12), fillPaint);
            strokePaint.setColor(accentColor);
            strokePaint.setStrokeWidth(dp(8));
            canvas.drawLine(pinX, pinY + dp(2), pinX, pinY + dp(20), strokePaint);
            fillPaint.setColor(darkMode ? Color.rgb(15, 20, 24) : Color.rgb(245, 247, 249));
            canvas.drawCircle(pinX, pinY - dp(7), dp(4), fillPaint);

            String seenText = seenAt > 0
                    ? "Last seen " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(seenAt))
                    : "Last seen not recorded";
            drawText(canvas, seenText, left + dp(72), top + dp(22), textColor, 13, true);
            String detail = !TextUtils.isEmpty(lat) && !TextUtils.isEmpty(lon)
                    ? lat + ", " + lon + " via " + provider
                    : "location unavailable; " + reason;
            drawText(canvas, detail, left + dp(72), top + dp(45), mutedColor, 11, false);
            drawText(canvas, "This is where this phone last saw the AirPods.", left + dp(72), top + dp(66), mutedColor, 10, false);
        }

        private int batteryValue(int value, int fallback) {
            return value >= 0 ? value : fallback;
        }

        private String percentText(int value) {
            return value >= 0 ? value + "%" : "--";
        }

        private void drawCenteredText(Canvas canvas, String value, float x, float y, int color, int sp, boolean bold) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            drawTextConfigured(canvas, value, x, y, color, sp, bold);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawText(Canvas canvas, String value, float x, float y, int color, int sp, boolean bold) {
            textPaint.setTextAlign(Paint.Align.LEFT);
            drawTextConfigured(canvas, value, x, y, color, sp, bold);
        }

        private void drawTextConfigured(Canvas canvas, String value, float x, float y, int color, int sp, boolean bold) {
            textPaint.setColor(color);
            textPaint.setTextSize(dp(sp));
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
            canvas.drawText(value, x, y, textPaint);
        }

        private int alpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        private float dp(int value) {
            return value * density;
        }
    }

    private static final class AncPathDiagramView extends View {
        private final boolean darkMode;
        private final int textColor;
        private final int mutedColor;
        private final int accentColor;
        private final int warningColor;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        AncPathDiagramView(Context context, boolean darkMode, int textColor, int mutedColor, int accentColor, int warningColor) {
            super(context);
            this.darkMode = darkMode;
            this.textColor = textColor;
            this.mutedColor = mutedColor;
            this.accentColor = accentColor;
            this.warningColor = warningColor;
            this.density = context.getResources().getDisplayMetrics().density;
            setContentDescription("ANC path test diagram: AirPods audio connects, AACP needs classic L2CAP, public Android opens LE and hidden classic socket is blocked, so normal app ANC control is blocked.");
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int desiredHeight = Math.round(dp(510));
            int height = resolveSize(desiredHeight, heightMeasureSpec);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            float gap = dp(10);
            float boxHeight = dp(56);
            float branchHeight = dp(70);
            float x = dp(2);
            float y = dp(6);
            float full = width - dp(4);

            Node phone = drawNode(canvas, x, y, full, boxHeight, "Phone audio", "A2DP + HFP active", successFill(), successStroke());
            y += boxHeight + gap;
            Node app = drawNode(canvas, x, y, full, boxHeight, "App ANC button", "Command path reached", successFill(), successStroke());
            drawArrow(canvas, phone.cx(), phone.bottom, app.cx(), app.top);

            y += boxHeight + gap;
            Node aacp = drawNode(canvas, x, y, full, boxHeight, "AACP target", "Classic L2CAP PSM 0x1001", neutralFill(), accentColor);
            drawArrow(canvas, app.cx(), app.bottom, aacp.cx(), aacp.top);

            y += boxHeight + dp(16);
            float branchWidth = (full - gap) / 2f;
            Node publicApi = drawNode(canvas, x, y, branchWidth, branchHeight, "Public API", "Opened L2CAP_LE; read ret -1", errorFill(), warningColor);
            Node hiddenApi = drawNode(canvas, x + branchWidth + gap, y, branchWidth, branchHeight, "Hidden classic", "createL2capSocket blocked", errorFill(), warningColor);
            drawArrow(canvas, aacp.cx(), aacp.bottom, publicApi.cx(), publicApi.top);
            drawArrow(canvas, aacp.cx(), aacp.bottom, hiddenApi.cx(), hiddenApi.top);

            y += branchHeight + gap;
            Node conclusion = drawNode(canvas, x, y, full, boxHeight + dp(6), "Current result", "Normal app cannot control ANC here", blockedFill(), warningColor);
            drawArrow(canvas, publicApi.cx(), publicApi.bottom, conclusion.cx() - dp(64), conclusion.top);
            drawArrow(canvas, hiddenApi.cx(), hiddenApi.bottom, conclusion.cx() + dp(64), conclusion.top);

            y += boxHeight + dp(18);
            drawNode(canvas, x, y, full, boxHeight + dp(20), "Next path", "Shizuku, root, or system-level Bluetooth service", nextFill(), accentColor);
        }

        private Node drawNode(Canvas canvas, float left, float top, float width, float height, String title, String detail, int fill, int stroke) {
            rect.set(left, top, left + width, top + height);
            fillPaint.setColor(fill);
            canvas.drawRoundRect(rect, dp(8), dp(8), fillPaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1));
            strokePaint.setColor(stroke);
            canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint);

            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextSize(dp(13));
            textPaint.setColor(textColor);
            float textX = left + dp(12);
            float textY = top + dp(22);
            canvas.drawText(title, textX, textY, textPaint);

            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(dp(11));
            textPaint.setColor(mutedColor);
            drawWrapped(canvas, detail, textX, textY + dp(18), width - dp(24));
            return new Node(left, top, left + width, top + height);
        }

        private void drawWrapped(Canvas canvas, String text, float x, float y, float maxWidth) {
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            float lineHeight = dp(14);
            for (String word : words) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (textPaint.measureText(candidate) > maxWidth && line.length() > 0) {
                    canvas.drawText(line.toString(), x, y, textPaint);
                    line.setLength(0);
                    line.append(word);
                    y += lineHeight;
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            if (line.length() > 0) {
                canvas.drawText(line.toString(), x, y, textPaint);
            }
        }

        private void drawArrow(Canvas canvas, float startX, float startY, float endX, float endY) {
            arrowPaint.setColor(mutedColor);
            arrowPaint.setStrokeWidth(dp(2));
            arrowPaint.setStyle(Paint.Style.STROKE);
            float midY = (startY + endY) / 2f;
            canvas.drawLine(startX, startY, startX, midY, arrowPaint);
            canvas.drawLine(startX, midY, endX, midY, arrowPaint);
            canvas.drawLine(endX, midY, endX, endY - dp(6), arrowPaint);
            arrowPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(endX, endY - dp(4), dp(3), arrowPaint);
        }

        private int successFill() {
            return darkMode ? Color.rgb(26, 58, 48) : Color.rgb(224, 247, 235);
        }

        private int successStroke() {
            return darkMode ? Color.rgb(67, 154, 118) : Color.rgb(45, 140, 94);
        }

        private int neutralFill() {
            return darkMode ? Color.rgb(30, 45, 59) : Color.rgb(230, 240, 250);
        }

        private int errorFill() {
            return darkMode ? Color.rgb(75, 40, 43) : Color.rgb(255, 232, 232);
        }

        private int blockedFill() {
            return darkMode ? Color.rgb(72, 57, 34) : Color.rgb(255, 244, 214);
        }

        private int nextFill() {
            return darkMode ? Color.rgb(48, 42, 72) : Color.rgb(239, 235, 255);
        }

        private float dp(int value) {
            return value * density;
        }

        private static final class Node {
            final float left;
            final float top;
            final float right;
            final float bottom;

            Node(float left, float top, float right, float bottom) {
                this.left = left;
                this.top = top;
                this.right = right;
                this.bottom = bottom;
            }

            float cx() {
                return (left + right) / 2f;
            }
        }
    }
}

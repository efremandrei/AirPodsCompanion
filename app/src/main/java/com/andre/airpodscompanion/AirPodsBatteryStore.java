package com.andre.airpodscompanion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class AirPodsBatteryStore {
    static final String PREFS = "airpods_companion_prefs";
    static final String ACTION_REFRESH = "com.andre.airpodscompanion.action.REFRESH_WIDGET";

    private static final String KEY_NAME = "widget_device_name";
    private static final String KEY_LEFT = "widget_left";
    private static final String KEY_RIGHT = "widget_right";
    private static final String KEY_CASE = "widget_case";
    private static final String KEY_COMBINED = "widget_combined";
    private static final String KEY_UPDATED = "widget_updated";
    private static final String KEY_SOURCE = "widget_source";
    private static final String KEY_NOTE = "widget_note";
    private static final String KEY_LEFT_IN_EAR = "widget_left_in_ear";
    private static final String KEY_RIGHT_IN_EAR = "widget_right_in_ear";
    private static final String KEY_LID_STATE = "widget_lid_state";
    private static final String KEY_LEFT_CHARGING = "widget_left_charging";
    private static final String KEY_RIGHT_CHARGING = "widget_right_charging";
    private static final String KEY_CASE_CHARGING = "widget_case_charging";
    private static final String KEY_MODEL = "widget_model";
    private static final String KEY_MIC = "widget_microphone";
    private static final String KEY_IN_CASE = "widget_in_case";

    private AirPodsBatteryStore() {
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(
                prefs.getString(KEY_NAME, "AirPods"),
                prefs.getInt(KEY_LEFT, -1),
                prefs.getInt(KEY_RIGHT, -1),
                prefs.getInt(KEY_CASE, -1),
                prefs.getInt(KEY_COMBINED, -1),
                prefs.getLong(KEY_UPDATED, 0L),
                prefs.getString(KEY_SOURCE, "Waiting for battery data"),
                prefs.getString(KEY_NOTE, "Open the app with AirPods nearby to refresh."),
                prefs.getInt(KEY_LEFT_IN_EAR, -1),
                prefs.getInt(KEY_RIGHT_IN_EAR, -1),
                prefs.getString(KEY_LID_STATE, "Unknown"),
                prefs.getInt(KEY_LEFT_CHARGING, -1),
                prefs.getInt(KEY_RIGHT_CHARGING, -1),
                prefs.getInt(KEY_CASE_CHARGING, -1),
                prefs.getString(KEY_MODEL, "Unknown model"),
                prefs.getString(KEY_MIC, "Unknown"),
                prefs.getString(KEY_IN_CASE, "Unknown")
        );
    }

    static void write(Context context, Snapshot snapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_NAME, snapshot.deviceName)
                .putInt(KEY_LEFT, snapshot.left)
                .putInt(KEY_RIGHT, snapshot.right)
                .putInt(KEY_CASE, snapshot.caseBattery)
                .putInt(KEY_COMBINED, snapshot.combined)
                .putLong(KEY_UPDATED, snapshot.updatedAt)
                .putString(KEY_SOURCE, snapshot.source)
                .putString(KEY_NOTE, snapshot.note)
                .putInt(KEY_LEFT_IN_EAR, snapshot.leftInEar)
                .putInt(KEY_RIGHT_IN_EAR, snapshot.rightInEar)
                .putString(KEY_LID_STATE, snapshot.lidState)
                .putInt(KEY_LEFT_CHARGING, snapshot.leftCharging)
                .putInt(KEY_RIGHT_CHARGING, snapshot.rightCharging)
                .putInt(KEY_CASE_CHARGING, snapshot.caseCharging)
                .putString(KEY_MODEL, snapshot.model)
                .putString(KEY_MIC, snapshot.microphone)
                .putString(KEY_IN_CASE, snapshot.inCaseState)
                .apply();
        updateWidgets(context);
    }

    static void updateWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, AirPodsBatteryWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        Snapshot snapshot = read(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_airpods_battery);
        views.setTextViewText(R.id.widgetTitle, snapshot.modelTitle());
        views.setTextViewText(R.id.widgetLeft, formatPart("L", snapshot.left, snapshot.combined));
        views.setTextViewText(R.id.widgetRight, formatPart("R", snapshot.right, snapshot.combined));
        views.setTextViewText(R.id.widgetCase, formatPart("Case", snapshot.caseBattery, -1));
        views.setTextViewText(R.id.widgetWearState, snapshot.stateText());
        views.setTextViewText(R.id.widgetHardwareState, snapshot.hardwareText());
        views.setTextViewText(R.id.widgetStatus, status(snapshot));

        PendingIntent openApp = PendingIntent.getActivity(
                context,
                100,
                new Intent(context, MainActivity.class),
                pendingFlags()
        );
        views.setOnClickPendingIntent(R.id.widgetRoot, openApp);
        manager.updateAppWidget(appWidgetId, views);
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static String formatPart(String label, int value, int fallback) {
        if (value >= 0) {
            return String.format(Locale.US, "%s %d%%", label, value);
        }
        if (fallback >= 0 && ("L".equals(label) || "R".equals(label))) {
            return String.format(Locale.US, "%s %d%%*", label, fallback);
        }
        return label + " --";
    }

    private static String status(Snapshot snapshot) {
        String updated = snapshot.updatedAt > 0
                ? DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(snapshot.updatedAt))
                : "not yet";
        return snapshot.source + " · " + updated;
    }

    static final class Snapshot {
        final String deviceName;
        final int left;
        final int right;
        final int caseBattery;
        final int combined;
        final long updatedAt;
        final String source;
        final String note;
        final int leftInEar;
        final int rightInEar;
        final String lidState;
        final int leftCharging;
        final int rightCharging;
        final int caseCharging;
        final String model;
        final String microphone;
        final String inCaseState;

        Snapshot(String deviceName, int left, int right, int caseBattery, int combined, long updatedAt, String source, String note) {
            this(deviceName, left, right, caseBattery, combined, updatedAt, source, note, -1, -1, "Unknown", -1, -1, -1, "Unknown model", "Unknown", "Unknown");
        }

        Snapshot(
                String deviceName,
                int left,
                int right,
                int caseBattery,
                int combined,
                long updatedAt,
                String source,
                String note,
                int leftInEar,
                int rightInEar,
                String lidState,
                int leftCharging,
                int rightCharging,
                int caseCharging
        ) {
            this(deviceName, left, right, caseBattery, combined, updatedAt, source, note, leftInEar, rightInEar, lidState, leftCharging, rightCharging, caseCharging, "Unknown model", "Unknown", "Unknown");
        }

        Snapshot(
                String deviceName,
                int left,
                int right,
                int caseBattery,
                int combined,
                long updatedAt,
                String source,
                String note,
                int leftInEar,
                int rightInEar,
                String lidState,
                int leftCharging,
                int rightCharging,
                int caseCharging,
                String model,
                String microphone,
                String inCaseState
        ) {
            this.deviceName = deviceName == null || deviceName.trim().isEmpty() ? "AirPods" : deviceName;
            this.left = left;
            this.right = right;
            this.caseBattery = caseBattery;
            this.combined = combined;
            this.updatedAt = updatedAt;
            this.source = source == null ? "Battery data" : source;
            this.note = note == null ? "" : note;
            this.leftInEar = leftInEar;
            this.rightInEar = rightInEar;
            this.lidState = lidState == null || lidState.trim().isEmpty() ? "Unknown" : lidState;
            this.leftCharging = leftCharging;
            this.rightCharging = rightCharging;
            this.caseCharging = caseCharging;
            this.model = model == null || model.trim().isEmpty() ? "Unknown model" : model;
            this.microphone = microphone == null || microphone.trim().isEmpty() ? "Unknown" : microphone;
            this.inCaseState = inCaseState == null || inCaseState.trim().isEmpty() ? "Unknown" : inCaseState;
        }

        String detailText() {
            String leftText = left >= 0 ? left + "%" : "unknown";
            String rightText = right >= 0 ? right + "%" : "unknown";
            String caseText = caseBattery >= 0 ? caseBattery + "%" : "unknown";
            if (left < 0 && right < 0 && combined >= 0) {
                leftText = combined + "%*";
                rightText = combined + "%*";
            }
            return "Left " + leftText + chargeSuffix(leftCharging)
                    + " · Right " + rightText + chargeSuffix(rightCharging)
                    + " · Case " + caseText + chargeSuffix(caseCharging);
        }

        String stateText() {
            return "Ear " + boolText(leftInEar, "L") + "/" + boolText(rightInEar, "R")
                    + " · Lid " + lidState;
        }

        String modelTitle() {
            return model.startsWith("Unknown") ? deviceName : model;
        }

        String hardwareText() {
            return "Model " + model + " · Mic " + microphone + " · Case " + inCaseState;
        }

        private String chargeSuffix(int charging) {
            return charging == 1 ? " charging" : "";
        }

        private String boolText(int state, String label) {
            if (state == 1) {
                return label + " in";
            }
            if (state == 0) {
                return label + " out";
            }
            return label + " ?";
        }
    }
}

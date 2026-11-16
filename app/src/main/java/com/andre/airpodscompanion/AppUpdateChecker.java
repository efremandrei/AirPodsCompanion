package com.andre.airpodscompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppUpdateChecker {
    private static final String TAG = "AppUpdateChecker";
    private static final String PREFS = "app_update_checker";
    private static final String LAST_ATTEMPT = "last_attempt_millis";
    private static final String LAST_SUCCESSFUL_CHECK = "last_successful_check_millis";
    private static final long ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long RETRY_AFTER_FAILURE_MILLIS = 2L * 60L * 60L * 1000L;
    private static final String RELEASES_API_URL = "https://api.github.com/repos/efremandrei/AirPodsCompanion/releases/latest";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECK_IN_PROGRESS = new AtomicBoolean();

    private AppUpdateChecker() {
    }

    public static void checkDaily(Activity activity) {
        Context appContext = activity.getApplicationContext();
        if (!beginCheck(appContext, false)) {
            return;
        }
        executeCheck(appContext, activity, false, null);
    }

    public static void checkNow(Activity activity) {
        Context appContext = activity.getApplicationContext();
        if (!beginCheck(appContext, true)) {
            Toast.makeText(activity, "An update check is already running.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
        executeCheck(appContext, activity, true, null);
    }

    public static void checkFromBoot(Context context, BroadcastReceiver.PendingResult pendingResult) {
        Context appContext = context.getApplicationContext();
        if (!beginCheck(appContext, false)) {
            pendingResult.finish();
            return;
        }
        executeCheck(appContext, null, false, pendingResult);
    }

    private static void executeCheck(Context appContext, Activity activity, boolean showResult,
                                     BroadcastReceiver.PendingResult pendingResult) {
        EXECUTOR.execute(() -> {
            try {
                UpdateCheckResult result = findUpdate(appContext);
                if (result.successful) {
                    prefs(appContext).edit().putLong(LAST_SUCCESSFUL_CHECK, System.currentTimeMillis()).apply();
                }
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        if (result.update != null) {
                            showDialog(activity, result.update);
                        } else if (showResult) {
                            Toast.makeText(activity,
                                    result.successful ? "You're up to date." : "Couldn't check for updates. Try again later.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } else if (result.update != null) {
                    showNotification(appContext, result.update);
                }
            } finally {
                CHECK_IN_PROGRESS.set(false);
                if (pendingResult != null) {
                    pendingResult.finish();
                }
            }
        });
    }

    private static boolean beginCheck(Context context, boolean force) {
        if (!force) {
            SharedPreferences preferences = prefs(context);
            long now = System.currentTimeMillis();
            long lastSuccess = preferences.getLong(LAST_SUCCESSFUL_CHECK, 0L);
            long lastAttempt = preferences.getLong(LAST_ATTEMPT, 0L);
            if (now - lastSuccess < ONE_DAY_MILLIS
                    || now - lastAttempt < RETRY_AFTER_FAILURE_MILLIS) {
                return false;
            }
        }
        if (!CHECK_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }
        prefs(context).edit().putLong(LAST_ATTEMPT, System.currentTimeMillis()).apply();
        return true;
    }


    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static UpdateCheckResult findUpdate(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API_URL).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "AndroidAppUpdateChecker");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return UpdateCheckResult.failed();
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JSONObject release = new JSONObject(body.toString());
            String tag = release.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                return UpdateCheckResult.failed();
            }
            String releaseUrl = release.optString("html_url", "");
            String downloadUrl = releaseUrl;
            String assetName = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.optJSONObject(index);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                        assetName = name;
                        downloadUrl = asset.optString("browser_download_url", releaseUrl);
                        break;
                    }
                }
            }
            if (downloadUrl.isEmpty()) {
                downloadUrl = releaseUrl;
            }
            if (downloadUrl.isEmpty()) {
                return UpdateCheckResult.failed();
            }
            UpdateInfo update = isNewer(context, tag, assetName)
                    ? new UpdateInfo(tag, assetName, downloadUrl)
                    : null;
            return UpdateCheckResult.succeeded(update);
        } catch (Exception error) {
            Log.w(TAG, "Update check failed", error);
            return UpdateCheckResult.failed();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isNewer(Context context, String tag, String assetName) {
        int latestBuild = Math.max(extractBuildNumber(tag), extractBuildNumber(assetName));
        if (latestBuild > currentVersionCode(context)) {
            return true;
        }
        String latestVersion = normalizeVersion(tag);
        String currentVersion = normalizeVersion(currentVersionName(context));
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    private static int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception error) {
            return 0;
        }
    }

    private static String currentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception error) {
            return "";
        }
    }

    private static int extractBuildNumber(String text) {
        Matcher matcher = Pattern.compile("(?i)(?:build|versionCode|code)[-_ ]?(\\d+)").matcher(text == null ? "" : text);
        int value = -1;
        while (matcher.find()) {
            value = Math.max(value, parseInt(matcher.group(1), -1));
        }
        return value;
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[^0-9]+");
        String[] rightParts = right.split("[^0-9]+");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < leftParts.length ? parseInt(leftParts[index], 0) : 0;
            int rightValue = index < rightParts.length ? parseInt(rightParts[index], 0) : 0;
            if (leftValue != rightValue) {
                return leftValue < rightValue ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static void showDialog(Activity activity, UpdateInfo update) {
        new AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("AirPods Companion " + update.tag + " is available. Download " + update.displayName() + " to update.")
            .setPositiveButton(update.assetName.isEmpty() ? "View release" : "Download APK",
                    (dialog, which) -> openDownload(activity, update.downloadUrl))
            .setNegativeButton("Later", null)
            .show();
    }

    private static void showNotification(Context context, UpdateInfo update) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String channelId = "app_updates";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                channelId,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ));
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl));
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            7021,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        int icon = context.getApplicationInfo().icon != 0
            ? context.getApplicationInfo().icon
            : android.R.drawable.stat_sys_download_done;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, channelId)
            : new Notification.Builder(context);
        builder.setSmallIcon(icon)
            .setContentTitle("Update available")
            .setContentText("Tap to download " + update.displayName())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);
        manager.notify(7021, builder.build());
    }

    private static void openDownload(Context context, String downloadUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
        context.startActivity(intent);
    }

    private static final class UpdateCheckResult {
        final boolean successful;
        final UpdateInfo update;

        private UpdateCheckResult(boolean successful, UpdateInfo update) {
            this.successful = successful;
            this.update = update;
        }

        static UpdateCheckResult succeeded(UpdateInfo update) {
            return new UpdateCheckResult(true, update);
        }

        static UpdateCheckResult failed() {
            return new UpdateCheckResult(false, null);
        }
    }

    private static final class UpdateInfo {
        final String tag;
        final String assetName;
        final String downloadUrl;

        UpdateInfo(String tag, String assetName, String downloadUrl) {
            this.tag = tag;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
        }

        String displayName() {
            return assetName == null || assetName.isEmpty() ? tag : assetName;
        }
    }
}

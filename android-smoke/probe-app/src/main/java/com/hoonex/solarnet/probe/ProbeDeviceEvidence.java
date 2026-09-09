package com.hoonex.solarnet.probe;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.FileInputStream;
import java.security.MessageDigest;

final class ProbeDeviceEvidence {
    private ProbeDeviceEvidence() { }

    static ProbeEvidenceSnapshot capture(Context context, String sourceSha, String apkSha256) {
        int batteryPercent = -1;
        double batteryTemperatureC = Double.NaN;
        int batteryVoltageMv = -1;

        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0)
                batteryPercent = Math.round(level * 100f / scale);

            int temperatureTenthsC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temperatureTenthsC != Integer.MIN_VALUE)
                batteryTemperatureC = temperatureTenthsC / 10.0;

            batteryVoltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        }

        int thermalStatus = -1;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) thermalStatus = power.getCurrentThermalStatus();
        }

        return new ProbeEvidenceSnapshot(
                System.currentTimeMillis(),
                batteryPercent,
                batteryTemperatureC,
                batteryVoltageMv,
                thermalStatus,
                sourceSha,
                apkSha256);
    }

    static String computeInstalledApkSha256(Context context) {
        return sha256File(context.getApplicationInfo().sourceDir);
    }

    private static String sha256File(String path) {
        if (path == null || path.isEmpty()) return "unknown";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream input = new FileInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return hex.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}

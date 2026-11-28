package io.local.rootward.shizuku;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

/**
 * User-requested spot readings. The dialog never changes a setting or runs a sampler in the
 * background.
 */
final class PowerCareDialog {
  private static final String LINEAGE = "org.lineageos.lineageparts";
  private static long lastSample = -10000;
  private static float load = Float.NaN;

  private static Intent chargingSettings() {
    return new Intent("org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS").setPackage(LINEAGE);
  }

  private static Intent systemActivity(Activity a, Intent intent) {
    PackageManager pm = a.getPackageManager();
    for (ResolveInfo candidate :
        pm.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY)) {
      ActivityInfo info = candidate.activityInfo;
      if (info != null
          && info.exported
          && (info.permission == null
              || a.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED))
        return new Intent(intent).setComponent(new ComponentName(info.packageName, info.name));
    }
    return null;
  }

  static void settings(Activity a, boolean saver) {
    Intent[] choices =
        saver
            ? new Intent[] {new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)}
            : new Intent[] {
              chargingSettings(),
              new Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
              new Intent(Settings.ACTION_SETTINGS)
            };
    for (Intent choice : choices) {
      Intent target = systemActivity(a, choice);
      if (target == null) continue;
      try {
        a.startActivity(target);
        return;
      } catch (ActivityNotFoundException | SecurityException unavailable) {
        // A system component can disappear or become restricted after resolution.
      }
    }
    Toast.makeText(a, saver ? "本机未提供可打开的省电设置" : "请手动打开系统设置中的「电池」", Toast.LENGTH_LONG).show();
  }

  static void open(Activity a) {
    AlertDialog d =
        new AlertDialog.Builder(a)
            .setTitle("供电与散热诊断")
            .setMessage(snapshot(a))
            .setNegativeButton("关闭", null)
            .setNeutralButton("刷新", null)
            .setPositiveButton("系统电池设置", (dialog, which) -> settings(a, false))
            .create();
    d.show();
    d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> d.setMessage(snapshot(a)));
  }

  private static String snapshot(Activity a) {
    PowerManager power = a.getSystemService(PowerManager.class);
    int thermal = -1;
    if (power != null && Build.VERSION.SDK_INT >= 29) {
      try {
        thermal = power.getCurrentThermalStatus();
      } catch (RuntimeException unavailable) {
        // Other readings are still usable if the thermal service is unavailable.
      }
    }
    long now = SystemClock.elapsedRealtime();
    long awake = SystemClock.uptimeMillis();
    long asleep = Math.max(0, now - awake);
    String background = "系统省电模式" + (power != null && power.isPowerSaveMode() ? "已开启" : "已关闭");
    if (Build.VERSION.SDK_INT >= 28) {
      ActivityManager manager = a.getSystemService(ActivityManager.class);
      if (manager != null) background += manager.isBackgroundRestricted()
          ? "。本应用受到后台限制，记录或重启恢复可能中断。可在系统应用信息的电池设置中调整。"
          : "。系统未报告本应用受到后台限制。";
    }
    if (power != null && Build.VERSION.SDK_INT >= 30 && now - lastSample >= 10000) {
      lastSample = now;
      try {
        load = power.getThermalHeadroom(0);
      } catch (RuntimeException unavailable) {
        load = Float.NaN;
      }
    }
    float ratio = power != null ? load : Float.NaN;
    BatteryManager bm = a.getSystemService(BatteryManager.class);
    int average = Integer.MIN_VALUE;
    long energy = Long.MIN_VALUE;
    if (bm != null) {
      average = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
      energy = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
    }
    Reading r = Reading.now(a);
    String note = PowerCareReport.advice(thermal, ratio, r != null ? r.plug : -1, average);
    String pressure =
        Build.VERSION.SDK_INT >= 29 ? PowerCareReport.thermalStatus(thermal) : "需要 Android 10 或以上";
    String headroom =
        Build.VERSION.SDK_INT >= 30 ? PowerCareReport.thermalLoad(ratio) : "需要 Android 11 或以上";
    return "供电  "
        + (r != null ? r.source() + " · " + r.state() : PowerCareReport.UNKNOWN)
        + "\n电池端平均电流  "
        + PowerCareReport.current(average)
        + "\n剩余能量  "
        + PowerCareReport.energy(energy)
        + "\n平均电流的统计时段取决于电池计量芯片。"
        + "\n\n电池温度  "
        + (r != null ? r.temperature() : "—")
        + "\n整机热状态  "
        + pressure
        + "\n当前热负荷  "
        + headroom
        + (Build.VERSION.SDK_INT >= 30 ? "\n热负荷读数最多每 10 秒更新一次。" : "")
        + (note.isEmpty() ? "" : "\n" + note)
        + "\n\n本次开机累计深睡  " + UsageWindow.duration(asleep)
        + (now > 0 ? "（" + Math.round(100d * asleep / now) + "%）" : "")
        + "\n开机经过  " + UsageWindow.duration(now)
        + "。统计整台设备进入挂起的时间，息屏时也可能保持唤醒。\n" + background
        + "\n\n"
        + PowerCareReport.guide(
            Build.MANUFACTURER, Build.MODEL, systemActivity(a, chargingSettings()) != null);
  }
}

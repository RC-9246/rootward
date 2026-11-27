package io.local.rootward.shizuku;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import java.util.Locale;

final class Reading {
  final long time;
  final int level, temp, mv, plug, status, cycles, chargeUah, health;
  final Integer current;
  final boolean interactive;
  final long fullMs;

  private Reading(Context c, Intent i) {
    time = System.currentTimeMillis();
    interactive = c.getSystemService(android.os.PowerManager.class).isInteractive();
    int raw = i.getIntExtra("level", -1), scale = i.getIntExtra("scale", 0);
    level = (raw >= 0 && raw <= scale && scale > 0) ? Math.round(raw * 100f / scale) : -1;
    temp = i.getIntExtra("temperature", Integer.MIN_VALUE);
    mv = i.getIntExtra("voltage", 0);
    plug = i.getIntExtra("plugged", -1);
    status = i.getIntExtra("status", 0);
    BatteryManager m = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
    health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
    cycles = Build.VERSION.SDK_INT >= 34 ? i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) : -1;
    chargeUah = m.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
    int ua = m.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
    current = ua == Integer.MIN_VALUE ? null : ua;
    fullMs = Build.VERSION.SDK_INT >= 28 ? m.computeChargeTimeRemaining() : -1;
  }

  static Reading now(Context c) {
    Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    return i == null ? null : new Reading(c, i);
  }

  String temperature() {
    return temp == Integer.MIN_VALUE ? "—" : String.format(Locale.CHINA, "%.1f °C", temp / 10d);
  }

  String healthLabel() {
    switch (health) {
      case BatteryManager.BATTERY_HEALTH_GOOD:
        return "正常";
      case BatteryManager.BATTERY_HEALTH_OVERHEAT:
        return "过热";
      case BatteryManager.BATTERY_HEALTH_COLD:
        return "低温";
      case BatteryManager.BATTERY_HEALTH_DEAD:
        return "失效";
      case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
        return "过压";
      case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
        return "故障";
      default:
        return "未知";
    }
  }

  String state() {
    switch (status) {
      case 2:
        return "正在充电";
      case 3:
        return "正在放电";
      case 4:
        return "未充电";
      case 5:
        return "已充满";
      default:
        return "状态未知";
    }
  }

  String source() {
    switch (plug) {
      case 0:
        return "使用电池";
      case 1:
        return "适配器";
      case 2:
        return "USB";
      case 4:
        return "无线";
      case 8:
        return "底座";
      default:
        return "电源未知";
    }
  }
}

package io.local.rootward.shizuku;

import android.os.Process;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Fixed battery operations hosted in a Shizuku UserService. */
public final class BatteryReader extends IBatteryReader.Stub {
  @Override
  public android.os.Bundle nativeLimit(
      String id, int low, int high, int side, int value, long expires) {
    NativeLimits.State s =
        NativeLimits.run(
            Process.myUid(),
            java.nio.file.Paths.get("/sys/class/power_supply"),
            id,
            low,
            high,
            side,
            value,
            (node, level) -> {
              long remaining = expires - android.os.SystemClock.elapsedRealtime();
              if (remaining <= 0 || remaining > 15000) throw new IOException("request expired");
              NativeLimits.submit(node, level);
            });
    android.os.Bundle b = new android.os.Bundle();
    b.putString("id", s.id);
    b.putString("name", s.name);
    b.putInt("start", s.start);
    b.putInt("end", s.end);
    b.putInt("writable", s.writable);
    b.putBoolean("confirmedChange", s.confirmedChange);
    b.putString("note", s.note);
    return b;
  }

  @Override
  public android.os.Bundle chargeMode(String expectedId, int action) {
    ChargeBehaviour.Result r =
        ChargeBehaviour.operate(Process.myUid(), expectedId == null ? "" : expectedId, action);
    android.os.Bundle result = new android.os.Bundle();
    result.putString("id", r.id);
    result.putInt("mode", r.mode);
    return result;
  }

  @Override
  public void destroy() {
    System.exit(0);
  }

  private static String field(String path) {
    try (BufferedReader in =
        new BufferedReader(new FileReader("/sys/class/power_supply/" + path))) {
      String s = in.readLine();
      if (s != null && s.matches("[a-zA-Z0-9 ._+:/\\[\\]-]{1,140}")) return s;
    } catch (IOException | SecurityException ignored) {
    }
    return "未上报或不可读";
  }

  private static void appendBatteryService(StringBuilder report) {
    java.lang.Process process = null;
    try {
      process =
          new ProcessBuilder("/system/bin/dumpsys", "-t", "3", "battery")
              .redirectErrorStream(true)
              .start();
      try (BufferedReader input =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        for (int n = 0; n < 120; n++) {
          String line = input.readLine();
          if (line == null) break;
          String[] field = line.trim().split(":\\s*", 2);
          if (field.length != 2 || !field[1].matches("[0-9]{1,12}")) continue;
          String label;
          switch (field[0]) {
            case "Max charging current":
              label = "系统充电电流上限（µA）";
              break;
            case "Max charging voltage":
              label = "系统充电电压上限（µV）";
              break;
            case "Charge counter":
              label = "系统剩余电荷（µAh）";
              break;
            default:
              continue;
          }
          report.append("\n").append(label).append("  ").append(field[1]);
        }
      }
    } catch (IOException | SecurityException ignored) {
    } finally {
      if (process != null) process.destroy();
    }
  }

  @Override
  public String readSnapshot() {
    if (Process.myUid() != 2000) throw new SecurityException("请以 ADB 模式启动 Shizuku");
    StringBuilder report = new StringBuilder("Shizuku · ADB shell (UID 2000)");
    String[][] names = {
      {"电池状态", "battery/status"},
      {"驱动循环次数", "battery/cycle_count"},
      {"满充容量（驱动原值，通常 µAh）", "battery/charge_full"},
      {"设计容量（驱动原值，通常 µAh）", "battery/charge_full_design"},
      {"USB 在线", "usb/online"},
      {"USB 类型", "usb/usb_type"},
      {"USB 电流上限（µA）", "usb/current_max"},
      {"USB 电压上限（µV）", "usb/voltage_max"},
      {"电池驱动温度（0.1°C）", "battery/temp"}
    };
    for (String[] n : names) report.append("\n").append(n[0]).append("  ").append(field(n[1]));
    appendBatteryService(report);
    return report.toString();
  }
}

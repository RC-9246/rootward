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
  public int chargeBehaviour(int action) {
    return ChargeBehaviour.run(Process.myUid(), action);
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

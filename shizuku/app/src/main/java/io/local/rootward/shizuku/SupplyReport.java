package io.local.rootward.shizuku;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** On-demand, read-only sysfs diagnostics for the shell UserService. */
final class SupplyReport {
  private static final String UNAVAILABLE = "未上报或不可读";
  private static final String NO_DATE = "未上报或无有效日期";

  static String read(Path supplies) {
    StringBuilder report = new StringBuilder();
    Path battery = null;
    try {
      battery = SystemBattery.select(supplies);
      report.append("\n系统电池节点  ").append(battery == null ? "未找到可确认的内置电池" : battery.getFileName());
    } catch (SystemBattery.Ambiguous e) {
      report.append("\n系统电池节点  有多个内置电池，无法确定读数归属");
    } catch (IOException | SecurityException | DirectoryIteratorException e) {
      report.append("\n系统电池节点  无法读取");
    }
    if (battery != null) {
      append(report, battery, "电池状态", "status");
      append(report, battery, "驱动循环次数", "cycle_count");
      append(report, battery, "满充容量（驱动原值，通常 µAh）", "charge_full");
      append(report, battery, "设计容量（驱动原值，通常 µAh）", "charge_full_design");
      append(report, battery, "电池驱动温度（0.1°C）", "temp");
    }
    report.append("\n电池生产日期  ").append(battery == null ? NO_DATE : manufactureDate(battery));

    try {
      Path usb = usbSupply(supplies);
      if (usb == null) return report.append("\nUSB 节点  未找到可唯一识别的 USB 电源").toString();
      report.append("\nUSB 节点  ").append(usb.getFileName());
      append(report, usb, "USB 在线", "online");
      append(report, usb, "USB 类型", "usb_type");
      append(report, usb, "USB 驱动瞬时电流（µA）", "current_now");
      append(report, usb, "USB 驱动瞬时电压（µV）", "voltage_now");
      append(report, usb, "USB 电流上限（µA）", "current_max");
      append(report, usb, "USB 电压上限（µV）", "voltage_max");
    } catch (IOException | SecurityException | DirectoryIteratorException e) {
      report.append("\nUSB 节点  无法读取");
    }
    return report.toString();
  }

  private static Path usbSupply(Path supplies) throws IOException {
    Map<Path, Path> candidates = new LinkedHashMap<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(supplies)) {
      for (Path node : entries) {
        String type = field(node, "type");
        boolean conventional = "usb".equals(node.getFileName().toString());
        if ("Device".equals(field(node, "scope"))) continue;
        if (conventional && UNAVAILABLE.equals(type) && Files.isDirectory(node)) return node;
        if (!type.matches("USB(?:_[A-Z0-9_]+)?")) continue;
        if (conventional) return node;
        candidates.put(node.toRealPath(), node);
      }
    }
    return candidates.size() == 1 ? candidates.values().iterator().next() : null;
  }

  private static String manufactureDate(Path battery) {
    try {
      int year = Integer.parseInt(field(battery, "manufacture_year"));
      int month = Integer.parseInt(field(battery, "manufacture_month"));
      int day = Integer.parseInt(field(battery, "manufacture_day"));
      return year > 0 && year <= 9999 ? LocalDate.of(year, month, day).toString() : NO_DATE;
    } catch (NumberFormatException | DateTimeException invalid) {
      return NO_DATE;
    }
  }

  private static void append(StringBuilder report, Path node, String label, String name) {
    report.append("\n").append(label).append("  ").append(field(node, name));
  }

  private static String field(Path node, String name) {
    try {
      String value = SystemBattery.text(node.resolve(name), 160);
      if (value.matches("[a-zA-Z0-9 ._+:/\\[\\]-]{1,140}")) return value;
    } catch (IOException | SecurityException ignored) {
      // Readable properties remain available when an individual attribute is absent.
    }
    return UNAVAILABLE;
  }

  private SupplyReport() {}
}

package io.local.rootward.shizuku;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class SupplyReportTest {
  private static void field(Path dir, String key, String value) throws Exception {
    Files.write(dir.resolve(key), (value + "\n").getBytes(StandardCharsets.US_ASCII));
  }

  private static void expect(String report, String value) {
    if (!report.contains(value)) throw new AssertionError("Expected " + value + " in " + report);
  }

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("rootward-telemetry");
    try {
      Path mouse = Files.createDirectory(root.resolve("mouse"));
      field(mouse, "type", "Battery");
      field(mouse, "scope", "Device");
      field(mouse, "charge_full", "987654");
      expect(SupplyReport.read(root), "未找到可确认的内置电池");

      Path pack = Files.createDirectory(root.resolve("BAT0"));
      field(pack, "type", "Battery");
      field(pack, "charge_full", "3600000");
      field(pack, "manufacture_year", "2024");
      field(pack, "manufacture_month", "2");
      field(pack, "manufacture_day", "29");
      Path usb = Files.createDirectory(root.resolve("charger_port"));
      field(usb, "type", "USB_PD");
      field(usb, "online", "1");
      field(usb, "usb_type", "SDP [PD] PD_PPS");
      String data = SupplyReport.read(root);
      expect(data, "系统电池节点  BAT0");
      expect(data, "3600000");
      expect(data, "电池生产日期  2024-02-29");
      expect(data, "USB 节点  charger_port");
      expect(data, "USB 类型  SDP [PD] PD_PPS");
      if (data.contains("987654")) throw new AssertionError("peripheral leaked");

      field(pack, "manufacture_year", "2023");
      expect(SupplyReport.read(root), "电池生产日期  未上报或无有效日期");
      Files.delete(pack.resolve("manufacture_year"));
      expect(SupplyReport.read(root), "电池生产日期  未上报或无有效日期");
      Files.createSymbolicLink(root.resolve("battery"), pack);
      expect(SupplyReport.read(root), "3600000");
      Path other = Files.createDirectory(root.resolve("auxiliary"));
      field(other, "type", "Battery");
      field(other, "scope", "System");
      data = SupplyReport.read(root);
      expect(data, "有多个内置电池");
      if (data.contains("3600000")) throw new AssertionError("ambiguous data leaked");
      field(other, "present", "0");
      expect(SupplyReport.read(root), "3600000");

      Path usb2 = Files.createDirectory(root.resolve("second_port"));
      field(usb2, "type", "USB");
      expect(SupplyReport.read(root), "未找到可唯一识别的 USB 电源");
      Files.createSymbolicLink(root.resolve("usb"), usb);
      expect(SupplyReport.read(root), "USB 节点  usb");
      field(pack, "present", "0");
      expect(SupplyReport.read(root), "未找到可确认的内置电池");
      System.out.println("SupplyReport tests passed");
    } finally {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
          Files.delete(p);
      }
    }
  }
}

package io.local.rootward.shizuku;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class AppBatteryUsageTest {
  static AppBatteryUsage parse(String lines, int caller) throws IOException {
    return AppBatteryUsage.read(
        new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)), caller);
  }

  static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }

  static void failure(byte[] data, String expected) throws IOException {
    try {
      AppBatteryUsage.read(new ByteArrayInputStream(data), 10001);
      throw new AssertionError("Accepted invalid input: " + expected);
    } catch (AppBatteryUsage.Unavailable e) {
      check(e.getMessage().contains(expected), e.getMessage());
    }
  }

  public static void main(String[] args) throws Exception {
    int caller = 1010002;
    String lines =
        "9,0,i,vers,35,186,build,build\n"
            + "9,0,i,uid,10002,com.shared.two\n9,0,i,uid,10002,com.shared.one\n"
            + "9,0,i,uid,10002,com.other.profile\n9,0,i,uid,1010002,com.user.ten\n"
            + "9,0,i,uid,10002,invalid name\n9,0,i,uid,1110002,com.private.secret\n"
            + "9,1010002,l,pwi,uid,12.5,0,9,999\r\n9,1010002,l,pwi,uid,12.5,0,9,999\n"
            + "9,1010002,l,pwi,scrn,9999,0\n9,10002,l,pwi,uid,99999,0\n"
            + "9,1110002,l,pwi,uid,999,0\n9,1010012,l,pwi,uid,99,1\n"
            + "9,1010003,l,pwi,uid,0.0001\n9,1010004,l,pwi,uid,0\n9,1010005,l,pwi,uid,NaN\n"
            + "9,1010006,l,pwi,uid,-9\n9,1010007,l,pwi,uid,1e800\n9,1010008,c,pwi,uid,100,0\n"
            + "9,1010009,l,pwi,uid,1,0\n9,1010009,l,pwi,uid,2,0\n"
            + "9,1001000,l,pwi,uid,100,0\n9,1099999,l,pwi,uid,100,0\n9,1020000,l,pwi,uid,100,0\n"
            + "9,1010010,l,pwi,uid,8.25,0\n9,1010011,l,pwi,uid,8.25,0\n"
            + "9,1010002,l,wl,private.wakelock,80\n";
    String report =
        parse(lines, caller)
            .report(
                uid -> {
                  check(uid / 100000 == 10, "cross-user package lookup");
                  if (uid == caller)
                    return new String[] {
                      "com.shared.one", "com.user.ten", "com.shared.two", "invalid name"
                    };
                  throw new SecurityException();
                });
    check(report.contains("共 4 个应用 UID") && report.contains("共享 UID，4 个包"), report);
    check(report.contains("com.shared.one、com.shared.two、com.user.ten"), report);
    check(
        report.contains("12.5 mAh") && report.contains("<0.001 mAh") && report.contains("包名未提供"),
        report);
    check(report.indexOf("UID 1010010") < report.indexOf("UID 1010011"), "unstable ties");
    for (String excluded :
        new String[] {"other.profile", "private", "invalid name", "999", "1010009", "1010012"})
      check(!report.contains(excluded), excluded);
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 25; i++)
      many.append("9,").append(10000 + i).append(",l,pwi,uid,").append(i + 1).append(",0\n");
    String top = parse(many.toString(), 10004).report(uid -> null);
    check(
        top.contains("25 个应用 UID")
            && top.contains("前 10 项")
            && top.contains("UID 10015")
            && !top.contains("UID 10014")
            && top.indexOf("UID 10024") < top.indexOf("UID 10015"),
        top);
    check(parse("9,0,i,vers,35\n", caller).report(u -> null).contains("尚未提供"), "no estimates");
    check(
        parse("9,0,l,pwi,scrn,1.4,1", caller).report(u -> null).contains("暂无"), "no ordinary UIDs");
    for (String[] row :
        new String[][] {
          {"Permission Denial: xyz", "拒绝"},
          {"*** SERVICE DUMP TIMEOUT ***", "超时"},
          {"unknown", "未返回"}
        }) failure(row[0].getBytes(StandardCharsets.UTF_8), row[1]);
    byte[] huge = new byte[8 * 1024 * 1024 + 1];
    Arrays.fill(huge, (byte) 'A');
    failure(huge, "上限");
    System.out.println("AppBatteryUsageTest passed");
  }
}

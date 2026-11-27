package io.local.rootward.shizuku;

import java.util.Locale;

final class PowerCareReport {
  static final String UNKNOWN = "未上报";

  static String current(int ua) {
    if (ua == Integer.MIN_VALUE) return UNKNOWN;
    if (ua == 0) return "0 mA";
    return String.format(
        Locale.CHINA, "%.2f mA（%s）", Math.abs(ua / 1000d), ua > 0 ? "流入电池" : "由电池流出");
  }

  static String energy(long nwh) {
    return nwh < 0 ? UNKNOWN : String.format(Locale.CHINA, "%.2f Wh", nwh / 1e9);
  }

  static String thermalStatus(int status) {
    String[] labels = {"未报告节流", "轻度节流", "中度节流", "严重节流", "危急", "紧急", "即将热关机"};
    return status >= 0 && status < labels.length ? labels[status] : UNKNOWN;
  }

  static String thermalLoad(float ratio) {
    return Float.isFinite(ratio) && ratio >= 0
        ? String.format(Locale.CHINA, "%.2f（1.00 为严重节流参考线）", ratio)
        : UNKNOWN;
  }

  static String advice(int status, float ratio, int plugged, int average) {
    if (status >= 4 && status <= 6) return "停止高负载任务，断开充电，让设备在阴凉通风处降温。";
    if (status == 2 || status == 3 || (Float.isFinite(ratio) && ratio >= 1))
      return "暂停游戏、导航等高负载任务，让设备散热后再比较读数。";
    if (plugged > 0 && average < 0 && average != Integer.MIN_VALUE)
      return "当前已接电，近期平均电流朝电池外流。若持续掉电，先降低负载，再检查电源和线缆。";
    return "";
  }

  static String guide(String manufacturer, String model, boolean lineage) {
    if (lineage) return "本机提供 LineageOS 充电控制入口。打开后可查看此设备支持的充电方式。";
    if ("Google".equalsIgnoreCase(manufacturer)
        && model.toLowerCase(Locale.ROOT).startsWith("pixel"))
      return "Pixel 可在系统设置的「电池 → 电池健康 → 充电优化」查看选项。Pixel 6a 及后续机型支持 80% 上限，可用选项随系统版本变化。";
    if ("samsung".equalsIgnoreCase(manufacturer))
      return "Galaxy 可在系统设置的「电池 → 电池保护」查看最高保护或自适应保护。上限档位以本机 One UI 显示为准。";
    if ("OnePlus".equalsIgnoreCase(manufacturer))
      return "一加 8T 的原厂手册列出「设置 → 电池 → 充电优化」，用于按使用习惯调整充电。部分 OxygenOS 版本将智能充电放在「电池 → 电池健康」。请以本机系统为准。";
    return "在系统设置的「电池」中查看充电保护、智能充电或充电上限。可用选项由设备和系统版本决定。";
  }
}

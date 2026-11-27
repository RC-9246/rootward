package io.local.rootward.shizuku;

final class BatteryAnalysisTest {
  static void check(boolean pass) {
    if (!pass) throw new AssertionError();
  }

  // A percentage sampled with integer rounding while the counter moves between percent ticks.
  static void charge(BatteryAnalysis analysis, int run, int capacity, boolean quantized) {
    long start = run * 86400000L;
    for (int i = 0; i <= 50; i++) {
      int pct = 50 + i / 2;
      int uah = capacity * 10 * pct + (quantized || i % 2 == 0 ? 0 : capacity * 5);
      analysis.add(start + i * 60000L, run, pct, uah, 1, 2, 1);
    }
    analysis.add(start + 51 * 60000L, run, 75, 0, 0, 3, 0);
  }

  public static void main(String[] args) {
    BatteryAnalysis report = new BatteryAnalysis(10L * 86400000);
    charge(report, 1, 4000, false);
    charge(report, 2, 4100, false);
    charge(report, 3, 3900, false);
    charge(report, 4, 2200, false);
    charge(report, 5, 4000, true);
    report.closeConnection();
    String text = report.describe();
    check(text.contains("5 段接电，4 段") && text.contains("保留 3 段，剔除 1 段"));
    check(text.contains("约 4000 mAh") && text.contains("3900–4100 mAh"));

    BatteryAnalysis ambiguous = new BatteryAnalysis(10L * 86400000);
    charge(ambiguous, 1, 2100, false);
    charge(ambiguous, 2, 2200, false);
    charge(ambiguous, 3, 4100, false);
    charge(ambiguous, 4, 4200, false);
    ambiguous.closeConnection();
    check(ambiguous.describe().contains("暂缓估算"));

    BatteryAnalysis insufficient = new BatteryAnalysis(10L * 86400000);
    charge(insufficient, 1, 4000, false);
    for (int i = 0; i < 51; i++) {
      // A pause and an old row missing the counter split the observation into shorter spans.
      insufficient.add(
          2 * 86400000L + i * 60000L,
          2,
          50 + i / 2,
          i == 24 ? 0 : 2000000 + i * 20000,
          1,
          i == 40 ? 4 : 2,
          1);
    }
    insufficient.closeConnection();
    check(insufficient.describe().contains("1 段通过") && insufficient.describe().contains("目前样本不足"));

    BatteryAnalysis old = new BatteryAnalysis(10L * 86400000);
    old.add(0, 1, 67, 0, 0, 3, -1);
    old.add(60000, 1, 66, 0, 0, 3, 1);
    old.add(120000, 1, 65, 0, 0, 3, 0);
    old.add(180000, 1, 65, 0, 0, 3, 0);
    old.add(240000, 1, 40, 0, 0, 3, 0); // Sudden gauge adjustment.
    old.add(600000, 1, 39, 0, 0, 3, 0); // Missing interval.
    old.add(660000, 2, 38, 0, 0, 3, 0); // New service segment.
    old.add(720000, 2, 38, 0, 1, 2, 0); // Cable edge.
    old.closeConnection();
    check(old.unplugged.time == 180000 && old.unplugged.netDrop == 2);
    check(old.interactive.time == 60000 && old.inactive.time == 60000);
    check(old.describe().contains("尚无有效的剩余电荷计数"));
    ThresholdPace pace = new ThresholdPace();
    for (int i = 0; i <= 10; i++) pace.add(i * 60000L, 1, 50 + i, 1, 2);
    check(pace.describe(600000, 60, 1, 2, 50, 75, false).contains("约 15 分钟"));
    check(pace.describe(600000, 60, 1, 2, 50, 75, true).contains("暂缓"));
    check(pace.describe(800000, 60, 1, 2, 50, 75, false).contains("不足"));
    pace.add(660000, 1, 60, 0, 3);
    check(pace.describe(660000, 60, 0, 3, 50, 75, false).contains("不足"));
    for (int i = 1; i <= 8; i++) pace.add((11 + i) * 60000L, 1, 60 - i, 0, 3);
    check(pace.describe(19 * 60000L, 52, 0, 3, 50, 75, false).contains("5 分钟以内"));
    BatteryAnalysis weeks = new BatteryAnalysis(30L * 86400000);
    for (int day : new int[] {17, 25}) {
      weeks.add(day * 86400000L, day, 80, 0, 0, 3, -1);
      weeks.add(day * 86400000L + 60000, day, 78, 0, 0, 3, -1);
    }
    check(weeks.currentWeek.netDrop == 2 && weeks.previousWeek.netDrop == 2);
    System.out.println(
        "Battery analysis: rate segmentation, legacy rows, charge quality and consensus passed");
  }
}

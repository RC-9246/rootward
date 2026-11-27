package io.local.rootward.shizuku;

public class UsageWindowTest {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    UsageWindow w = new UsageWindow(30000, 75, 40);
    w.add(0, 1, 75, 420, 1, 2, 1800000);
    w.add(60000, 1, 77, 430, 1, 4, 1820000);
    w.add(120000, 1, 76, 395, 0, 3, 0);
    w.add(180000, 1, 73, 390, 0, 3, 0);
    w.add(700000, 1, 65, 350, 1, 2, 1250000);
    w.add(760000, 1, 66, 360, 1, 2, 1280000);
    w.add(780000, 2, -1, Integer.MIN_VALUE, 1, 4, 0);
    w.add(800000, 2, 60, 350, 1, 4, 0);
    check(w.samples == 7 && w.covered == 230000 && w.onPower == 170000);
    check(w.charging == 90000 && w.highTime == 150000 && w.overlap == 90000);
    check(w.connections == 3 && w.recent.getFirst().start == 30000);
    check(w.recent.getFirst().last == 77 && w.recent.getFirst().peak == 430);
    check(w.describe(7).contains("近 7 天") && w.describe(7).contains("— → 60%"));
    check(w.describe(7).contains("+30 mAh") && !w.describe(7).contains("+20 mAh"));
    UsageWindow empty = new UsageWindow(0, 75, 40);
    empty.add(500, 1, 62, 360, 1, 2, 1280000);
    check(empty.covered == 0 && empty.connections == 0);
    System.out.println("Historical coverage, charge segments and missing samples passed");
  }
}

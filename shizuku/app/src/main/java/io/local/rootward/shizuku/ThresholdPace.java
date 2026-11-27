package io.local.rootward.shizuku;

import java.util.ArrayDeque;

/** Estimates time to a configured threshold from a recent uninterrupted percentage trend. */
final class ThresholdPace {
  static final long WINDOW = 30 * 60000L;

  private static final class Point {
    final long time;
    final int level;

    Point(long time, int level) {
      this.time = time;
      this.level = level;
    }
  }

  private final ArrayDeque<Point> points = new ArrayDeque<>();
  private long segment;
  private int source, state;

  void add(long time, long session, int pct, int plug, int status) {
    Point last = points.peekLast();
    if (last != null
        && (session != segment
            || plug != source
            || status != state
            || time <= last.time
            || time - last.time > UsageWindow.MAX_GAP
            || Math.abs(pct - last.level) > 5
            || (status == 2 ? pct < last.level : pct > last.level))) points.clear();
    segment = session;
    source = plug;
    state = status;
    if (pct < 0 || pct > 100 || !(plug > 0 && status == 2 || plug == 0 && status == 3)) {
      points.clear();
      return;
    }
    while (!points.isEmpty() && time - points.peekFirst().time > WINDOW) points.removeFirst();
    points.addLast(new Point(time, pct));
  }

  String describe(long now, int level, int plug, int status, int lo, int hi, boolean chargingHeld) {
    boolean charging = plug > 0 && status == 2;
    if (level < 0 || level > 100 || !(charging || plug == 0 && status == 3)) return "等待稳定的充电或离电状态。";
    if (charging && chargingHeld) return "当前温控或养护阶段要求停充，暂缓估算。";
    int target = charging ? hi : lo;
    int distance = charging ? target - level : level - target;
    String label = (charging ? "充至上限 " : "离电回落至 ") + target + "%";
    if (distance <= 0) return "已达到" + (charging ? "上限 " : "恢复下限 ") + target + "%。";
    Point last = points.peekLast(), first = points.peekFirst();
    String insufficient = label + "　连续样本或电量跨度不足。";
    if (points.size() < 6
        || plug != source
        || status != state
        || now < last.time
        || now - last.time > UsageWindow.MAX_GAP
        || Math.abs(level - last.level) > 1) return insufficient;
    long covered = last.time - first.time;
    int span = Math.abs(last.level - first.level);
    if (covered < 5 * 60000L || span < 3 || distance > 3 * span) return insufficient;
    Point middle = first;
    for (Point p : points) {
      if (p.time > first.time + covered / 2) break;
      middle = p;
    }
    long a = middle.time - first.time, b = last.time - middle.time;
    double leftRate = Math.abs(middle.level - first.level) / (double) Math.max(1, a);
    double rightRate = Math.abs(last.level - middle.level) / (double) Math.max(1, b);
    if (Math.min(leftRate, rightRate) <= 0
        || Math.max(leftRate, rightRate) > 3 * Math.min(leftRate, rightRate))
      return label + "　最近速率变化较大。";
    long minutes = Math.round(distance * (covered / 60000.0) / span);
    return label
        + (minutes < 5 ? "　约 5 分钟以内" : "　约 " + (5 * Math.round(minutes / 5.0)) + " 分钟")
        + "（参考近 "
        + covered / 60000
        + " 分钟，变化 "
        + span
        + " 个百分点）。";
  }
}

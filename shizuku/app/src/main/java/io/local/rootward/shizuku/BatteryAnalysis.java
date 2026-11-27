package io.local.rootward.shizuku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Aggregates observed intervals. A power connection contributes at most one capacity estimate. */
final class BatteryAnalysis {
  private static final long MIN_RUN_MS = 5 * 60000L;
  private static final int MIN_SPAN = 20;

  static final class Discharge {
    long time, netDrop;

    void add(long dt, int drop) {
      time += dt;
      netDrop += drop;
    }

    String describe() {
      if (time == 0) return "无连续样本";
      String duration = UsageWindow.duration(time);
      String rate =
          time >= 30 * 60000L && netDrop >= 2
              ? String.format(Locale.CHINA, "，%.1f 个百分点/小时", netDrop * 3600000.0 / time)
              : "";
      return duration
          + "，净变化 "
          + (netDrop > 0 ? "−" : netDrop < 0 ? "+" : "")
          + Math.abs(netDrop)
          + " 个百分点"
          + rate;
    }
  }

  static final class Estimate {
    final int span;
    final double mah;

    Estimate(int span, double mah) {
      this.span = span;
      this.mah = mah;
    }
  }

  /** Fit a line to check that the two endpoint counters describe a sufficiently regular charge. */
  private static final class Run {
    final long start;
    final int firstPct, firstUah;
    long end;
    int lastPct, lastUah, n, rises;
    double sumX, sumY, xx, xy, yy;

    Run(long time, int pct, int uah) {
      start = time;
      firstPct = lastPct = pct;
      firstUah = lastUah = uah;
    }

    void sample(long time, int pct, int uah) {
      if (pct > lastPct) rises++;
      double x = pct - firstPct, y = (uah - (long) firstUah) / 1000.0;
      n++;
      sumX += x;
      sumY += y;
      xx += x * x;
      xy += x * y;
      yy += y * y;
      end = time;
      lastPct = pct;
      lastUah = uah;
    }

    Estimate estimate() {
      int span = lastPct - firstPct;
      if (span < MIN_SPAN || n < 6 || rises < 5 || end - start < MIN_RUN_MS) return null;
      double xVar = xx - sumX * sumX / n,
          yVar = yy - sumY * sumY / n,
          covariance = xy - sumX * sumY / n;
      if (xVar <= 0 || yVar <= 0 || covariance <= 0) return null;
      double explained = covariance * covariance / xVar;
      double mah = (lastUah - (long) firstUah) / (10.0 * span);
      // Reject noisy resets, gross unit mismatches, and counters indistinguishable from
      // a linear copy of the integer percentage. Neither a design table nor a single current is
      // used.
      if (explained / yVar < .95
          || mah < 100
          || mah > 100000
          || Math.sqrt(Math.max(0, yVar - explained) / n) < mah * .0001) return null;
      return new Estimate(span, mah);
    }
  }

  private static final long WEEK = 7L * 86400000;
  private final long now;
  final ThresholdPace pace = new ThresholdPace();
  final Discharge currentWeek = new Discharge(), previousWeek = new Discharge();

  BatteryAnalysis(long now) {
    this.now = now;
  }

  final Discharge unplugged = new Discharge(),
      interactive = new Discharge(),
      inactive = new Discharge();
  private final List<Estimate> candidates = new ArrayList<>();
  private Run run;
  private Estimate best;
  private boolean powered;
  int samples, poweredSegments, counterSamples;
  private long previousTime = -1, previousSession;
  private int previousPct, previousPlug, previousInteractive;

  void add(long time, long session, int pct, int uah, int plug, int status, int screen) {
    if (time >= now - ThresholdPace.WINDOW) pace.add(time, session, pct, plug, status);
    boolean continuous =
        previousTime >= 0
            && session == previousSession
            && time > previousTime
            && time - previousTime <= UsageWindow.MAX_GAP;
    if (!continuous || plug != previousPlug) closeConnection();
    boolean knownPct = pct >= 0 && pct <= 100;
    if (continuous
        && plug == 0
        && previousPlug == 0
        && knownPct
        && previousPct >= 0
        && previousPct <= 100
        && Math.abs(pct - previousPct) <= 5) {
      int drop = previousPct - pct;
      long dt = time - previousTime;
      unplugged.add(dt, drop);
      if (previousTime >= now - WEEK) currentWeek.add(dt, drop);
      else if (previousTime >= now - 2 * WEEK && time <= now - WEEK) previousWeek.add(dt, drop);
      if (previousInteractive == 1) interactive.add(dt, drop);
      if (previousInteractive == 0) inactive.add(dt, drop);
    }
    samples++;
    if (uah > 0) counterSamples++;
    if (plug > 0) {
      powered = true;
      boolean valid = status == 2 && pct >= 5 && pct <= 95 && uah > 0;
      if (!valid || (run != null && (pct < run.lastPct || uah < run.lastUah))) closeRun();
      if (valid) {
        if (run == null) run = new Run(time, pct, uah);
        run.sample(time, pct, uah);
      }
    }
    previousTime = time;
    previousSession = session;
    previousPct = pct;
    previousPlug = plug;
    previousInteractive = screen;
  }

  private void closeRun() {
    if (run == null) return;
    Estimate next = run.estimate();
    if (next != null && (best == null || next.span > best.span)) best = next;
    run = null;
  }

  void closeConnection() {
    closeRun();
    if (powered) poweredSegments++;
    if (best != null) candidates.add(best);
    powered = false;
    best = null;
  }

  private static double median(List<Double> sorted) {
    int n = sorted.size();
    return (sorted.get((n - 1) / 2) + sorted.get(n / 2)) / 2;
  }

  String describe() {
    StringBuilder out =
        new StringBuilder("近 30 天 · ")
            .append(samples)
            .append(" 个样本")
            .append("\n\n离电用量\n全部离电　")
            .append(unplugged.describe())
            .append("\n设备可交互　")
            .append(interactive.describe())
            .append("\n设备非交互　")
            .append(inactive.describe())
            .append("\n最近 7 天　")
            .append(currentWeek.describe())
            .append("\n此前 7 天　")
            .append(previousWeek.describe())
            .append("\n\n容量估算\n观察到 ")
            .append(poweredSegments)
            .append(" 段接电，")
            .append(candidates.size())
            .append(" 段通过单段质量筛查。");
    if (counterSamples == 0) return out.append("\n记录尚无有效的剩余电荷计数。").toString();
    if (candidates.size() < 3) return out.append("\n至少需要 3 段符合条件的充电，目前样本不足。").toString();

    List<Double> sorted = new ArrayList<>();
    for (Estimate candidate : candidates) sorted.add(candidate.mah);
    Collections.sort(sorted);
    double center = median(sorted);
    List<Double> retained = new ArrayList<>();
    int minSpan = 100, maxSpan = 0;
    for (Estimate candidate : candidates) {
      if (Math.abs(candidate.mah - center) > .15 * center) continue;
      retained.add(candidate.mah);
      minSpan = Math.min(minSpan, candidate.span);
      maxSpan = Math.max(maxSpan, candidate.span);
    }
    out.append("\n一致性筛查保留 ")
        .append(retained.size())
        .append(" 段，剔除 ")
        .append(candidates.size() - retained.size())
        .append(" 段。");
    if (retained.size() < 3 || retained.size() * 3 < candidates.size() * 2)
      return out.append(" 各段差异较大，暂缓估算。").toString();
    Collections.sort(retained);
    return out.append("\n按系统计数折算约 ")
        .append(Math.round(median(retained)))
        .append(" mAh")
        .append("\n纳入样本范围 ")
        .append(Math.round(retained.get(0)))
        .append("–")
        .append(Math.round(retained.get(retained.size() - 1)))
        .append(" mAh，单段跨度 ")
        .append(minSpan)
        .append("–")
        .append(maxSpan)
        .append(" 个百分点。")
        .append("\n双电芯沿用手机上报口径。此值用于跟踪同一设备，不能换算为寿命或健康度。")
        .toString();
  }
}

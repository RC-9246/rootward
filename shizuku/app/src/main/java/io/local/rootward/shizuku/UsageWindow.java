package io.local.rootward.shizuku;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;

/** Integrates adjacent observations only. Power connections can contain time spent waiting. */
final class UsageWindow {
  static final long MAX_GAP = 150000L;
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA);
  private static final int MISSING = Integer.MIN_VALUE;
  private final long from;
  private final int high, hot;
  private long last = -1, segment;
  private int pct, temp, plug, status, charge;
  int samples, connections;
  long covered, onPower, charging, highTime, hotTime, overlap;
  final ArrayDeque<Connection> recent = new ArrayDeque<>();
  private Connection active;

  static final class Connection {
    long start, end, charging;
    int first, last, peak, firstCharge, lastCharge;
    Connection(long start, int pct, int temp, int charge) {
      this.start = this.end = start;
      this.firstCharge = charge;
      this.first = this.last = pct;
      this.peak = temp;
    }
  }

  UsageWindow(long from, int high, int hot) {
    this.from = from;
    this.high = high;
    this.hot = hot * 10;
  }

  void add(long time, long session, int newPct, int newTemp, int newPlug, int newStatus, int newCharge) {
    boolean continuous = last >= 0 && time > last && time - last <= MAX_GAP && session == segment;
    if (!continuous) active = null;
    if (time >= from) samples++;
    long left = Math.max(from, last);
    if (continuous && time > left) {
      long dt = time - left;
      covered += dt;
      if (pct >= high) highTime += dt;
      if (temp >= hot) hotTime += dt;
      if (pct >= high && temp >= hot) overlap += dt;
      if (plug > 0) {
        onPower += dt;
        if (active == null) {
          active = new Connection(left, pct, temp, last < from ? 0 : charge);
          connections++;
          if (recent.size() == 8) recent.removeFirst();
          recent.addLast(active);
        }
        active.end = time;
        if (status == 2) { charging += dt; active.charging += dt; }
        if (plug == newPlug) {
          active.last = newPct;
          active.lastCharge = newCharge;
          active.peak = Math.max(active.peak, newTemp);
        }
      }
    }
    if (newPlug != plug) active = null;
    last = time;
    segment = session;
    pct = newPct;
    temp = newTemp;
    plug = newPlug;
    status = newStatus;
    charge = newCharge;
  }

  static String duration(long ms) {
    long min = ms / 60000L;
    if (ms > 0 && min == 0) return "不足 1 分钟";
    if (min < 60) return min + " 分钟";
    return min / 60 + " 小时 " + min % 60 + " 分钟";
  }

  private static String percent(int pct) { return pct < 0 ? "—" : pct + "%"; }

  String describe(int days) {
    String intro = "近 " + (days == 1 ? "24 小时" : days + " 天") + " · " + samples + " 个样本";
    if (covered == 0) return intro + "\n尚无连续读数可用于计时。";
    StringBuilder out = new StringBuilder(intro).append("，已覆盖 ").append(duration(covered))
        .append("\n电量 ≥").append(high).append("%　").append(duration(highTime))
        .append("\n温度 ≥").append(hot / 10).append("°C　").append(duration(hotTime))
        .append("\n高电量与高温重叠　").append(duration(overlap))
        .append("\n\n接电　").append(duration(onPower))
        .append("\n接电且系统报告充电　").append(duration(charging));
    if (connections == 0) return out.append("\n未观察到连续接电。").toString();
    out.append("\n\n接电记录 · 最近 ").append(recent.size()).append(" 段，共 ").append(connections).append(" 段");
    ZoneId zone = ZoneId.systemDefault();
    for (java.util.Iterator<Connection> it = recent.descendingIterator(); it.hasNext();) {
      Connection c = it.next();
      out.append("\n\n").append(CLOCK.format(Instant.ofEpochMilli(c.start).atZone(zone)))
          .append("　").append(duration(c.end - c.start))
          .append("\n").append(percent(c.first)).append(" → ").append(percent(c.last))
          .append("　最高 ").append(c.peak == MISSING ? "—" : String.format(Locale.CHINA, "%.1f°C", c.peak / 10d))
          .append("\n系统报告充电 ").append(duration(c.charging));
      if (c.firstCharge > 0 && c.lastCharge > 0) {
        long net = Math.round((c.lastCharge - (long) c.firstCharge) / 1000.0);
        out.append("　剩余电荷净变 ").append(net > 0 ? "+" : "").append(net).append(" mAh");
      }
    }
    return out.toString();
  }
}

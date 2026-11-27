package io.local.rootward.shizuku;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Extracts Android's since-charged UID power estimates; never retains unrelated checkin rows. */
final class AppBatteryUsage {
  interface Packages {
    String[] forUid(int uid);
  }

  static final class Unavailable extends IOException {
    Unavailable(String message) {
      super(message);
    }
  }

  private static final int PER_USER = 100000, LIMIT = 10, MAX_BYTES = 8 * 1024 * 1024;
  private static final Pattern PACKAGE =
      Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*");
  private final int user;
  private final Map<Integer, Double> usage = new HashMap<>();
  private final Set<Integer> ambiguous = new HashSet<>();
  private final Map<Integer, Set<String>> names = new HashMap<>();
  private boolean sawCheckin, sawPower;

  private AppBatteryUsage(int callerUid) {
    user = callerUid / PER_USER;
  }

  static AppBatteryUsage read(InputStream input, int callerUid) throws IOException {
    AppBatteryUsage result = new AppBatteryUsage(callerUid);
    // All supported checkin fields are ASCII. Single-byte decoding gives an exact byte limit.
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1))) {
      char[] chunk = new char[8192];
      int total = 0, count;
      StringBuilder line = new StringBuilder();
      boolean oversized = false;
      while ((count = in.read(chunk)) != -1) {
        total += count;
        if (total > MAX_BYTES) throw new Unavailable("统计输出超过读取上限，本次排名未生成。");
        for (int i = 0; i < count; i++) {
          char c = chunk[i];
          if (c == '\n') {
            if (!oversized) result.row(line.toString().trim());
            line.setLength(0);
            oversized = false;
          } else if (line.length() < 4096) line.append(c);
          else oversized = true; // Long timeline and process rows contain no supported fields.
        }
      }
      if (!oversized && line.length() != 0) result.row(line.toString().trim());
    }
    if (!result.sawCheckin) throw new Unavailable("系统未返回可识别的电池统计。");
    return result;
  }

  private static int number(String s) {
    try {
      int value = Integer.parseInt(s);
      return value >= 0 && s.charAt(0) != '+' ? value : -1;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static boolean ordinary(int uid) {
    int id = uid % PER_USER;
    return id >= 10000 && id <= 19999;
  }

  private void row(String line) throws Unavailable {
    if (line.isEmpty()) return;
    if (!Character.isDigit(line.charAt(0))) {
      String error = line.toLowerCase(Locale.ROOT);
      if (error.contains("permission denial") || error.startsWith("java.lang.securityexception"))
        throw new Unavailable("系统拒绝读取电池统计，请检查 ADB 模式的 Shizuku 授权。");
      if (error.contains("dump timeout") || error.contains("timed out"))
        throw new Unavailable("系统电池统计超时，请稍后重试。");
      return;
    }
    String[] f = line.split(",", 9);
    if (f.length < 4 || number(f[0]) < 1) return;
    if ("0".equals(f[1]) && "i".equals(f[2]) && "vers".equals(f[3])) sawCheckin = true;
    if ("0".equals(f[1]) && "i".equals(f[2]) && "uid".equals(f[3]) && f.length == 6) {
      int uid = number(f[4]);
      if (ordinary(uid)
          && (uid < PER_USER || uid / PER_USER == user)
          && f[5].length() <= 255
          && PACKAGE.matcher(f[5]).matches()) {
        names.computeIfAbsent(uid, key -> new TreeSet<>()).add(f[5]);
      }
      return;
    }
    if (!"l".equals(f[2]) || !"pwi".equals(f[3]) || f.length < 6) return;
    sawCheckin = sawPower = true;
    int uid = number(f[1]);
    if (!"uid".equals(f[4])
        || !ordinary(uid)
        || uid / PER_USER != user
        || ambiguous.contains(uid)
        || (f.length >= 7 && !"0".equals(f[6]))) return;
    try {
      if (!f[5].matches("[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?")) return;
      double mah = Double.parseDouble(f[5]);
      if (!(mah > 0 && mah <= 1e8)) return;
      Double previous = usage.put(uid, mah);
      if (previous != null && previous != mah) {
        usage.remove(uid);
        ambiguous.add(uid); // A single total is expected. Conflicting totals cannot be combined.
      }
    } catch (NumberFormatException ignored) {
    }
  }

  String report(Packages packages) {
    if (!sawPower) return "系统尚未提供本统计周期的耗电估算。可在离电使用一段时间后重试。";
    if (usage.isEmpty()) return "当前用户暂无系统可归属给普通应用的正耗电估算。";
    List<Integer> ids = new ArrayList<>(usage.keySet());
    ids.sort(
        (a, b) -> {
          int order = Double.compare(usage.get(b), usage.get(a));
          return order == 0 ? Integer.compare(a, b) : order;
        });
    StringBuilder out = new StringBuilder("自上次充满以来 · Android 系统估算\n");
    int count = Math.min(ids.size(), LIMIT);
    out.append("当前用户共 ")
        .append(ids.size())
        .append(" 个应用 UID 有估算，列出前 ")
        .append(count)
        .append(" 项。共享 UID 只能统计合计值。");
    DecimalFormat format =
        new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.ROOT));
    for (int i = 0; i < count; i++) {
      int uid = ids.get(i);
      double mah = usage.get(uid);
      String[] installed = null;
      try {
        installed = packages.forUid(uid);
      } catch (RuntimeException ignored) {
        // Missing package visibility still allows reporting this user's numeric UID and estimate.
      }
      // AOSP's i,uid mapping contains app IDs pooled across users. Validate every name against
      // packages actually installed for this full UID; a shared UID may differ between profiles.
      Set<String> labels = new TreeSet<>();
      Set<String> appNames = names.get(uid % PER_USER), fullNames = names.get(uid);
      if (installed != null) {
        for (String pkg : installed) {
          if (pkg == null) continue;
          if ((appNames != null && appNames.contains(pkg))
              || (fullNames != null && fullNames.contains(pkg))) {
            labels.add(pkg);
          }
        }
      }
      out.append("\n\n")
          .append(i + 1)
          .append(".  ")
          .append(mah < 0.001 ? "<0.001" : format.format(mah))
          .append(" mAh  ·  UID ")
          .append(uid);
      if (labels.isEmpty()) out.append("\n包名未提供或无法核对");
      else {
        out.append('\n');
        int index = 0;
        for (String pkg : labels) {
          if (index == 3) break;
          if (index++ != 0) out.append("、");
          out.append(pkg);
        }
        if (labels.size() > 3) out.append(" 等");
      }
      if (installed != null && installed.length > 1)
        out.append("（共享 UID，").append(installed.length).append(" 个包）");
    }
    return out.toString();
  }
}

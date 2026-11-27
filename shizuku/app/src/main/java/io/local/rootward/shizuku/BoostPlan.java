package io.local.rootward.shizuku;

import android.content.SharedPreferences;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Preference access is shared by the UI, foreground observer and optional driver. */
final class BoostPlan {
  static final String START = "boostStart";
  static final String ANNOUNCED = "boostAnnounced";
  static final int NOTICE = 36;
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA);

  static long start(SharedPreferences p) { return p.getLong(START, 0); }
  static boolean sameLimits(SharedPreferences p, int lo, int hi, int hot) {
    return p.getInt("boostLow", -1) == lo && p.getInt("boostHigh", -1) == hi
        && p.getInt("boostHeat", -1) == hot;
  }
  static BoostWindow.Stage stage(SharedPreferences p, long now, int phase, int level) {
    int lo = p.getInt("low", 50), hi = p.getInt("high", 75), hot = p.getInt("heat", 40);
    return BoostWindow.stage(start(p), now, sameLimits(p, lo, hi, hot), phase, level, lo, hi);
  }
  static String window(long start) {
    ZoneId zone = ZoneId.systemDefault();
    return CLOCK.format(Instant.ofEpochMilli(start).atZone(zone)) + " 至 "
        + CLOCK.format(Instant.ofEpochMilli(start + BoostWindow.HOUR).atZone(zone));
  }
  static void save(SharedPreferences p, long at) {
    p.edit().putLong(START, at).putInt("boostLow", p.getInt("low", 50))
        .putInt("boostHigh", p.getInt("high", 75)).putInt("boostHeat", p.getInt("heat", 40))
        .remove(ANNOUNCED).apply();
  }
  static SharedPreferences.Editor clear(SharedPreferences.Editor edit) {
    return edit.remove(START).remove("boostLow").remove("boostHigh").remove("boostHeat")
        .remove(ANNOUNCED);
  }
}

package io.local.rootward.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.SystemClock;
import java.util.Arrays;

/** Selected automation apps can query one recent observation. Nothing accepts control commands. */
public final class AutomationSignal extends ContentProvider {
  static final String RECIPIENT = "automationPackage";
  static final String ACTION = "io.local.rootward.shizuku.POLICY_CHANGED";
  static final Uri URI = Uri.parse("content://io.local.rootward.shizuku.automation/v1/state");
  private static final String[] COLUMNS = {"request", "phase", "level", "lower", "upper", "observed_at"};
  private static final long FRESH_MS = 150000;
  private static final class Sample {
    String destination, request, phase;
    int level, lower, upper;
    long time, elapsed, appointment;
  }
  private static Sample latest;
  private static long lastNotice;

  static synchronized void publish(Context c, SharedPreferences p, int phase, int level,
      int lower, int upper, int temperature, boolean boost) {
    String destination = p.getString(RECIPIENT, "");
    Sample s = new Sample();
    s.destination = destination;
    s.level = level;
    s.lower = lower;
    s.upper = upper;
    s.elapsed = SystemClock.elapsedRealtime();
    s.time = System.currentTimeMillis();
    s.appointment = boost ? BoostPlan.start(p) : 0;
    s.phase = boost ? "filling" : phase == ChargePhase.WAIT ? "waiting" : phase == ChargePhase.MANUAL ? "manual" : "filling";
    if ((!boost && phase == ChargePhase.WAIT) || p.getBoolean("thermal", false)
        || p.getBoolean(Reading.HEALTH_HOLD, false)) s.request = "pause";
    else if (level < 0 || level > 100 || temperature == Integer.MIN_VALUE) s.request = "unknown";
    else s.request = "allow";
    SmartOutlet.observe(c, s.request, s.appointment == 0 ? 0 : s.appointment + BoostWindow.HOUR);
    if (destination.isEmpty()) { latest = null; return; }
    boolean changed = latest == null || !destination.equals(latest.destination)
        || !s.phase.equals(latest.phase) || !s.request.equals(latest.request)
        || s.appointment != latest.appointment || s.lower != latest.lower || s.upper != latest.upper || s.elapsed - lastNotice >= 60000;
    latest = s;
    if (changed) {
      lastNotice = s.elapsed;
      c.sendBroadcast(new Intent(ACTION).setPackage(destination));
    }
  }

  static synchronized void stopped(Context c) {
    latest = null;
    String destination = ObserverApp.prefs(c).getString(RECIPIENT, "");
    if (!destination.isEmpty()) c.sendBroadcast(new Intent(ACTION).setPackage(destination));
  }

  static synchronized void select(Context c, SharedPreferences prefs, String destination) {
    String old = prefs.getString(RECIPIENT, "");
    if (old.equals(destination)) return;
    latest = null;
    if (destination.isEmpty()) prefs.edit().remove(RECIPIENT).apply();
    else prefs.edit().putString(RECIPIENT, destination).apply();
    if (!old.isEmpty()) c.sendBroadcast(new Intent(ACTION).setPackage(old));
  }

  @Override public boolean onCreate() { return true; }

  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    if (!URI.equals(uri) || (selection != null && !selection.isEmpty())
        || (args != null && args.length != 0) || (order != null && !order.isEmpty()))
      throw new IllegalArgumentException("Only the current state is available");
    SharedPreferences p = ObserverApp.prefs(getContext());
    String caller = getCallingPackage();
    if (caller == null || !caller.equals(p.getString(RECIPIENT, "")))
      throw new SecurityException("Select the receiving app in Rootward first");
    String[] columns = projection == null ? COLUMNS : projection;
    for (String column : columns) {
      if (!Arrays.asList(COLUMNS).contains(column)) throw new IllegalArgumentException("Unknown column");
    }
    MatrixCursor result = new MatrixCursor(columns, 1);
    synchronized (AutomationSignal.class) {
      Sample s = latest;
      if (s == null || !caller.equals(s.destination) || !p.getBoolean("watch", false)
          || SystemClock.elapsedRealtime() - s.elapsed > FRESH_MS) return result;
      if (s.appointment > 0 && (BoostPlan.start(p) != s.appointment
          || BoostPlan.stage(p, System.currentTimeMillis(), p.getInt("phase", ChargePhase.FILL), s.level)
              != BoostWindow.Stage.OPEN)) return result;
      Object[] values = {s.request, s.phase, s.level < 0 ? null : s.level, s.lower, s.upper, s.time};
      MatrixCursor.RowBuilder row = result.newRow();
      for (String column : columns) row.add(values[Arrays.asList(COLUMNS).indexOf(column)]);
    }
    return result;
  }

  @Override public String getType(Uri uri) {
    return URI.equals(uri) ? "vnd.android.cursor.item/vnd.io.local.rootward.policy" : null;
  }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}

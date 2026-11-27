package io.local.rootward.shizuku;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.PointF;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class Archive extends SQLiteOpenHelper {
  Archive(Context c) {
    super(c, "history.sqlite", null, 3);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE samples (id INTEGER PRIMARY KEY, ts INTEGER, session INTEGER, pct INTEGER,"
            + " temp INTEGER, mv INTEGER, ua INTEGER, plugged INTEGER, status INTEGER, charge_uah INTEGER, interactive INTEGER)");
    db.execSQL("CREATE INDEX by_time ON samples(ts)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    if (oldV < 2) db.execSQL("ALTER TABLE samples ADD COLUMN charge_uah INTEGER");
    if (oldV < 3) db.execSQL("ALTER TABLE samples ADD COLUMN interactive INTEGER");
  }

  void record(Reading r, long session) {
    ContentValues v = new ContentValues();
    v.put("ts", r.time);
    v.put("session", session);
    v.put("pct", r.level);
    if (r.temp != Integer.MIN_VALUE) v.put("temp", r.temp);
    if (r.mv > 0) v.put("mv", r.mv);
    v.put("ua", r.current);
    v.put("plugged", r.plug);
    v.put("status", r.status);
    v.put("interactive", r.interactive ? 1 : 0);
    if (r.chargeUah > 0) v.put("charge_uah", r.chargeUah);
    SQLiteDatabase db = getWritableDatabase();
    db.insertOrThrow("samples", null, v);
    db.delete("samples", "ts < ?", new String[] {String.valueOf(r.time - 30L * 86400000)});
  }

  UsageWindow window(int days, int high, int hot) {
    long now = System.currentTimeMillis();
    long from = now - days * 86400000L;
    UsageWindow report = new UsageWindow(from, high, hot);
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT ts,session,pct,temp,plugged,status,charge_uah FROM samples WHERE ts>=? AND ts<=? ORDER BY id",
        new String[] {String.valueOf(from - UsageWindow.MAX_GAP), String.valueOf(now)})) {
      while (c.moveToNext())
        report.add(c.getLong(0), c.getLong(1), c.getInt(2),
            c.isNull(3) ? Integer.MIN_VALUE : c.getInt(3), c.getInt(4), c.getInt(5), c.isNull(6) ? 0 : c.getInt(6));
    }
    return report;
  }

  BatteryAnalysis analysis() {
    long now = System.currentTimeMillis();
    BatteryAnalysis result = new BatteryAnalysis(now);
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT ts,session,pct,charge_uah,plugged,status,interactive FROM samples"
            + " WHERE ts>=? AND ts<=? ORDER BY id",
        new String[] {String.valueOf(now - 30L * 86400000), String.valueOf(now)})) {
      while (c.moveToNext()) {
        result.add(c.getLong(0), c.getLong(1), c.getInt(2), c.isNull(3) ? 0 : c.getInt(3),
            c.getInt(4), c.getInt(5), c.isNull(6) ? -1 : c.getInt(6));
      }
    }
    result.closeConnection();
    return result;
  }

  ArrayList<PointF> recentLevels() {
    ArrayList<PointF> out = new ArrayList<>();
    long now = System.currentTimeMillis();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT ts,pct,session FROM samples WHERE ts>=? ORDER BY id",
                new String[] {String.valueOf(now - 3600000)})) {
      long last = 0, session = -1;
      while (c.moveToNext()) {
        long ts = c.getLong(0);
        long s = c.getLong(2);
        if (ts < last || ts - last > UsageWindow.MAX_GAP || s != session || c.getInt(1) < 0) out.add(null);
        if (c.getInt(1) >= 0)
          out.add(new PointF((float) ((ts - now + 3600000) / 3600000d), c.getInt(1) / 100f));
        last = ts;
        session = s;
      }
    }
    return out;
  }

  void exportCsv(OutputStream stream) throws IOException {
    if (stream == null) throw new IOException("文件不可写");
    try (OutputStreamWriter out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        Cursor c =
            getReadableDatabase()
                .rawQuery(
                    "SELECT ts,session,pct,temp,mv,ua,plugged,status,charge_uah,interactive FROM samples WHERE ts>=? ORDER"
                        + " BY id",
                    new String[] {String.valueOf(System.currentTimeMillis() - 30L * 86400000)})) {
      out.write(
          "unix_millis,sampling_segment,battery_percent,temperature_deci_c,battery_mV,battery_current_uA,android_plugged,android_status,remaining_charge_uAh,android_interactive\n");
      while (c.moveToNext()) {
        for (int i = 0; i < 10; i++) {
          if (i > 0) out.write(',');
          if (!c.isNull(i)) out.write(c.getString(i));
        }
        out.write('\n');
      }
    }
  }
}

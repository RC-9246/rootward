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
    super(c, "history.sqlite", null, 1);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE samples (id INTEGER PRIMARY KEY, ts INTEGER, session INTEGER, pct INTEGER,"
            + " temp INTEGER, mv INTEGER, ua INTEGER, plugged INTEGER, status INTEGER)");
    db.execSQL("CREATE INDEX by_time ON samples(ts)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

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
    SQLiteDatabase db = getWritableDatabase();
    db.insertOrThrow("samples", null, v);
    db.delete("samples", "ts < ?", new String[] {String.valueOf(r.time - 30L * 86400000)});
  }

  String summary(int hi, int hot) {
    long span = 0, high = 0, warm = 0, both = 0, last = 0, lastSession = -1;
    boolean wasHigh = false, wasWarm = false;
    int n = 0;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT ts,session,pct,temp FROM samples WHERE ts>=? ORDER BY id",
                new String[] {String.valueOf(System.currentTimeMillis() - 86400000)})) {
      while (c.moveToNext()) {
        long ts = c.getLong(0), s = c.getLong(1), dt = ts - last;
        if (s == lastSession && dt > 0 && dt <= 150000) {
          span += dt;
          if (wasHigh) high += dt;
          if (wasWarm) warm += dt;
          if (wasHigh && wasWarm) both += dt;
        }
        wasHigh = c.getInt(2) >= hi;
        wasWarm = !c.isNull(3) && c.getInt(3) >= hot * 10;
        last = ts;
        lastSession = s;
        n++;
      }
    }
    return "近 24 小时 · "
        + n
        + " 个样本，可计时 "
        + span / 60000
        + " 分钟\n≥"
        + hi
        + "% "
        + high / 60000
        + " 分钟  ·  ≥"
        + hot
        + "°C "
        + warm / 60000
        + " 分钟\n高电量与高温重叠 "
        + both / 60000
        + " 分钟";
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
        if (ts < last || ts - last > 150000 || s != session) out.add(null);
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
                    "SELECT ts,session,pct,temp,mv,ua,plugged,status FROM samples WHERE ts>=? ORDER"
                        + " BY id",
                    new String[] {String.valueOf(System.currentTimeMillis() - 30L * 86400000)})) {
      out.write(
          "unix_millis,sampling_segment,battery_percent,temperature_deci_c,battery_mV,battery_current_uA,android_plugged,android_status\n");
      while (c.moveToNext()) {
        for (int i = 0; i < 8; i++) {
          if (i > 0) out.write(',');
          if (!c.isNull(i)) out.write(c.getString(i));
        }
        out.write('\n');
      }
    }
  }
}

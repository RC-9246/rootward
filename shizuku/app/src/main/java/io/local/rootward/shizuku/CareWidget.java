package io.local.rootward.shizuku;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.widget.RemoteViews;
import java.util.Date;

public final class CareWidget extends AppWidgetProvider {
  private static final String REFRESH = "io.local.rootward.shizuku.WIDGET_REFRESH";

  @Override
  public void onReceive(Context c, Intent i) {
    if (REFRESH.equals(i.getAction())) refresh(c);
    else super.onReceive(c, i);
  }

  @Override
  public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
    update(c, manager, ids);
  }

  static void refresh(Context c) {
    AppWidgetManager manager = AppWidgetManager.getInstance(c);
    update(c, manager, manager.getAppWidgetIds(new ComponentName(c, CareWidget.class)));
  }

  private static void update(Context c, AppWidgetManager manager, int[] ids) {
    if (ids.length == 0) return;
    Reading r = Reading.now(c);
    SharedPreferences p = ObserverApp.prefs(c);
    RemoteViews view = new RemoteViews(c.getPackageName(), R.layout.care_widget);
    view.setTextViewText(
        R.id.widget_level,
        (r != null && r.level >= 0 ? r.level + "%" : "—")
            + "   "
            + (r == null ? "—" : r.temperature()));
    int lo = p.getInt("low", 50), hi = p.getInt("high", 75);
    String state = "记录已停";
    if (p.getBoolean("watch", false) && !WatchService.running) state = "等待记录服务";
    else if (p.getBoolean("watch", false)) {
      int phase = p.getInt("phase", ChargePhase.FILL);
      boolean hot =
          p.getBoolean("thermal", false)
              || (r != null && r.temp != Integer.MIN_VALUE && r.temp >= p.getInt("heat", 40) * 10);
      if (hot) state = "高温保护";
      else if (r == null || r.level < 0 || r.temp == Integer.MIN_VALUE) state = "等待有效读数";
      else if (BoostWindow.permits(
          BoostPlan.stage(p, r.time, phase, r.level), r.level, lo, hi, r.temp, false))
        state = "预约补电中";
      else if (phase == ChargePhase.WAIT) state = "等待回落";
      else if (phase == ChargePhase.MANUAL) state = "本次提前补电";
      else state = "允许充向上限";
    }
    view.setTextViewText(
        R.id.widget_state,
        (r == null ? "电池读数未知" : r.state()) + " · " + state + " · " + lo + "–" + hi + "%");
    view.setTextViewText(
        R.id.widget_refresh, DateFormat.getTimeFormat(c).format(new Date()) + "  刷新");
    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    view.setOnClickPendingIntent(
        R.id.widget_refresh,
        PendingIntent.getBroadcast(
            c, 0, new Intent(c, CareWidget.class).setAction(REFRESH), flags));
    view.setOnClickPendingIntent(
        R.id.widget_open,
        PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags));
    manager.updateAppWidget(ids, view);
  }
}

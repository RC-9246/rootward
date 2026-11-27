package io.local.rootward.shizuku;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.SizeF;
import android.widget.RemoteViews;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

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

  @Override
  public void onAppWidgetOptionsChanged(
      Context c, AppWidgetManager manager, int id, Bundle options) {
    update(c, manager, new int[] {id});
  }

  static void refresh(Context c) {
    AppWidgetManager manager = AppWidgetManager.getInstance(c);
    update(c, manager, manager.getAppWidgetIds(new ComponentName(c, CareWidget.class)));
  }

  private static void update(Context c, AppWidgetManager manager, int[] ids) {
    if (ids.length == 0) return;
    Reading r = Reading.now(c);
    SharedPreferences p = ObserverApp.prefs(c);
    String level = r != null && r.level >= 0 ? r.level + "%" : "—";
    String temp = r == null ? "—" : r.temperature();
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
    String fullState =
        (r == null ? "电池读数未知" : r.state()) + " · " + state + " · " + lo + "–" + hi + "%";
    String time = DateFormat.getTimeFormat(c).format(new Date());
    RemoteViews full = layout(c, false, level + "   " + temp, fullState, time);
    RemoteViews compact = layout(c, true, level, state, time);
    compact.setContentDescription(R.id.widget_open, level + "，" + temp + "，" + fullState);
    float scale = c.getResources().getConfiguration().fontScale;
    if (Build.VERSION.SDK_INT >= 31) {
      Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
      layouts.put(new SizeF(1, 1), compact);
      layouts.put(new SizeF(190 * scale, 24 + 88 * scale), full);
      manager.updateAppWidget(ids, new RemoteViews(layouts));
      return;
    }
    for (int id : ids) {
      Bundle opt = manager.getAppWidgetOptions(id);
      // Legacy hosts advertise portrait width/height as minWidth/maxHeight, landscape vice versa.
      RemoteViews portrait =
          fits(
                  opt,
                  AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                  AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                  scale)
              ? full
              : compact;
      RemoteViews landscape =
          fits(
                  opt,
                  AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                  AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                  scale)
              ? full
              : compact;
      manager.updateAppWidget(id, new RemoteViews(landscape, portrait));
    }
  }

  private static boolean fits(Bundle options, String widthKey, String heightKey, float fontScale) {
    int width = options.getInt(widthKey, 0);
    int height = options.getInt(heightKey, 0);
    if (width <= 0 || height <= 0) return fontScale <= 1.1f;
    if (width <= 0 || height <= 0) return true;
    return width >= 190 * fontScale && height >= 24 + 88 * fontScale;
  }

  private static RemoteViews layout(
      Context c, boolean compact, String level, String state, String time) {
    RemoteViews view =
        new RemoteViews(
            c.getPackageName(), compact ? R.layout.care_widget_compact : R.layout.care_widget);
    view.setTextViewText(R.id.widget_level, level);
    view.setTextViewText(R.id.widget_state, state);
    if (!compact) view.setTextViewText(R.id.widget_refresh, time + "  刷新");
    view.setContentDescription(R.id.widget_refresh, time + "，刷新电池读数");
    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    view.setOnClickPendingIntent(
        R.id.widget_refresh,
        PendingIntent.getBroadcast(
            c, 0, new Intent(c, CareWidget.class).setAction(REFRESH), flags));
    view.setOnClickPendingIntent(
        R.id.widget_open,
        PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags));
    return view;
  }
}

package io.local.rootward.shizuku;

import android.app.*;
import android.content.*;
import android.os.*;

public final class WatchService extends Service {
  private static final int BATTERY_HEALTH_NOTICE = 38;
  static final String STATUS = "watch",
      ALERT = "limit",
      UPDATE = "io.local.rootward.shizuku.UPDATE";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Archive archive;
  private CruiseDriver cruise;
  private long segment;
  private int lastLevel = -2, lastPlug = -2, lastStatus = -2, lastTemp = Integer.MIN_VALUE;
  private long lastSaved;
  private boolean lastInteractive;
  private boolean registered;
  static boolean running;
  private final Runnable timer =
      new Runnable() {
        public void run() {
          tick();
          long now = System.currentTimeMillis();
          long start = BoostPlan.start(ObserverApp.prefs(WatchService.this));
          long next = now < start ? start : start + BoostWindow.HOUR;
          long delay = start > 0 && next > now ? Math.min(next - now, 60000L) : 60000L;
          handler.postDelayed(this, delay);
        }
      };
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          tick();
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    running = true;
    archive = new Archive(this);
    cruise = new CruiseDriver(this);
    WatchNotices.ensureChannels(getSystemService(NotificationManager.class));
  }

  private Notification notice(String channel, String title, String detail, boolean ongoing) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b =
        WatchNotices.builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing);
    if (ongoing) {
      PendingIntent stop =
          PendingIntent.getService(
              this,
              1,
              new Intent(this, WatchService.class).setAction("stop"),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止记录", stop);
    }
    return b.build();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    SharedPreferences p = ObserverApp.prefs(this);
    getSystemService(NotificationManager.class).cancel(RestoreWatch.RECOVERY_NOTICE);
    if ((i != null && "stop".equals(i.getAction())) || !p.getBoolean("watch", false)) {
      BoostPlan.clear(p.edit()).putBoolean("watch", false).putBoolean("cruise", false).apply();
      getSystemService(NotificationManager.class).cancel(BoostPlan.NOTICE);
      startForeground(31, notice(STATUS, "归根正在停止记录", "检查本次巡航和充电插座状态", true));
      int[] remaining = {2};
      Runnable released = () -> { if (--remaining[0] == 0) stopSelf(id); };
      SmartOutlet.stop(this, released);
      cruise.stop(released);
      return START_NOT_STICKY;
    }
    startForeground(31, notice(STATUS, "归根正在记录", "电量、温度和充放电状态", true));
    if (!registered) {
      segment = System.currentTimeMillis();
      registered = true;
      IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
      filter.addAction(Intent.ACTION_SCREEN_ON);
      filter.addAction(Intent.ACTION_SCREEN_OFF);
      registerReceiver(receiver, filter);
      handler.post(timer);
    } else {
      handler.removeCallbacks(timer);
      timer.run();
    }
    return START_STICKY;
  }

  private void tick() {
    SharedPreferences p = ObserverApp.prefs(this);
    if (!p.getBoolean("watch", false)) return;
    Reading r = Reading.now(this);
    if (r == null) {
      AutomationSignal.publish(this, p, p.getInt("phase", ChargePhase.FILL), -1,
          p.getInt("low", 50), p.getInt("high", 75), Integer.MIN_VALUE, false);
      cruise.sample();
      return;
    }
    int lo = p.getInt("low", 50), hi = p.getInt("high", 75), hot = p.getInt("heat", 40);
    int old = p.getInt("phase", 0), phase = ChargePhase.sample(old, r.level, lo, hi);
    boolean thermal = p.getBoolean("thermal", false);
    boolean hardware = p.getBoolean("cruise", false);
    boolean outlet = SmartOutlet.prefs(this).getBoolean("active", false);
    NotificationManager nm = getSystemService(NotificationManager.class);
    boolean fault = r.healthHold(p);
    if (fault != p.getBoolean(Reading.HEALTH_HOLD, false)) {
      p.edit().putBoolean(Reading.HEALTH_HOLD, fault).apply();
      if (fault) {
        nm.notify(BATTERY_HEALTH_NOTICE, notice(ALERT, "电池健康异常 · " + r.healthLabel(),
            "自动补电建议已暂停。请查看系统电池状态。", false));
      } else nm.cancel(BATTERY_HEALTH_NOTICE);
    }
    if (phase != old) {
      p.edit().putInt("phase", phase).apply();
      String msg;
      if (phase == ChargePhase.WAIT) msg = hardware ? "达到巡航上限，正在核对驱动停充。" : outlet ? "已到上限，正在核对充电插座。" : "达到提醒上限，可拔线或手动暂停充电。";
      else msg = hardware ? "达到恢复下限，正在核对温度及驱动状态。" : outlet ? "已到下限，正在核对温度和充电插座。" : "达到恢复下限，需要时可接电或手动恢复。";
      if (fault && phase != ChargePhase.WAIT) msg = "已到恢复线，等待系统电池健康恢复正常。";
      nm.notify(32, notice(ALERT, r.level + "% · 电量提醒", msg, false));
    }
    if (!thermal && r.temp != Integer.MIN_VALUE && r.temp >= hot * 10) {
      p.edit().putBoolean("thermal", true).apply();
      nm.notify(
          33,
          notice(
              ALERT,
              "电池温度 " + r.temperature(),
              hardware ? "达到巡航温度上限，正在核对驱动停充。" : outlet ? "达到温度上限，正在核对充电插座。" : "可减少负载或手动暂停充电。",
              false));
    } else if (thermal && r.temp != Integer.MIN_VALUE && r.temp <= (hot - 2) * 10)
      p.edit().putBoolean("thermal", false).apply();
    boolean boost = checkBoost(p, r, phase, lo, hi, hardware);
    AutomationSignal.publish(this, p, phase, r.level, lo, hi, r.temp, boost);
    cruise.sample();
    if (lastSaved == 0
        || r.time - lastSaved >= 55000
        || r.time < lastSaved
        || r.interactive != lastInteractive
        || r.level != lastLevel
        || r.plug != lastPlug
        || r.status != lastStatus
        || r.temp != Integer.MIN_VALUE && Math.abs((long) r.temp - lastTemp) >= 10) {
      archive.record(r, segment);
      lastSaved = r.time;
      lastInteractive = r.interactive;
      lastLevel = r.level;
      lastPlug = r.plug;
      lastStatus = r.status;
      lastTemp = r.temp;
      nm.notify(
          31,
          notice(
              STATUS,
              "归根 · " + (r.level < 0 ? "—" : r.level + "%") + "  " + r.temperature(),
              r.source() + " · " + r.state(),
              true));
      sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
    }
    CareWidget.refresh(this);
  }

  private boolean checkBoost(SharedPreferences p, Reading r, int phase, int lo, int hi, boolean hardware) {
    BoostWindow.Stage stage = BoostPlan.stage(p, r.time, phase, r.level);
    if (stage == BoostWindow.Stage.NONE) return false;
    boolean announced = p.getBoolean(BoostPlan.ANNOUNCED, false);
    boolean allow = BoostWindow.permits(stage, r.level, lo, hi, r.temp,
        p.getBoolean("thermal", false) || r.healthHold(p));
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (stage == BoostWindow.Stage.FINISHED) {
      BoostPlan.clear(p.edit()).apply();
      if (announced || r.level >= hi) {
        String msg = r.level >= hi ? "已达到原上限 " + hi + "%，恢复等待下限。"
            : "已恢复常规电量与温控策略。";
        nm.notify(BoostPlan.NOTICE, notice(ALERT, "预约补电已结束", msg, false));
      } else nm.cancel(BoostPlan.NOTICE);
    } else if (allow != announced) {
      p.edit().putBoolean(BoostPlan.ANNOUNCED, allow).apply();
      String msg = allow ? "本次窗口内可补至 " + hi + "%。"
          + (hardware ? "归根正在核对驱动。" : "如需实际充电，请接入电源或由外部联动供电。")
          : "待电量与温度读数满足条件后，窗口内可继续。";
      nm.notify(BoostPlan.NOTICE, notice(ALERT, allow ? "已到预约补电时间" : "预约补电暂缓", msg, false));
    }
    return allow;
  }

  @Override
  public void onDestroy() {
    running = false;
    AutomationSignal.stopped(this);
    CareWidget.refresh(this);
    if (registered) unregisterReceiver(receiver);
    handler.removeCallbacks(timer);
    cruise.close();
    archive.close();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
